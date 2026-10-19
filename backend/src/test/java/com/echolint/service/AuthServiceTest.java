package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.domain.UserRole;
import com.echolint.dto.AuthUserResponse;
import com.echolint.dto.LoginRequest;
import com.echolint.dto.PasswordChangeRequest;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 会话认证：登录（校验 + 换 sessionId + 写入 SecurityContext）、失败 401、登出、改密。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SecurityContextRepository securityContextRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;

    private AuthService service;
    private AppUser alice;
    private AppUserDetails aliceDetails;

    @BeforeEach
    void setUp() {
        service = new AuthService(authenticationManager, userRepository, passwordEncoder,
                securityContextRepository, auditService);

        alice = new AppUser();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setPasswordHash("$2a$hashed");
        alice.setDisplayName("Alice");
        alice.setRole(UserRole.REVIEWER);
        alice.setEnabled(true);
        aliceDetails = new AppUserDetails(alice);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginInContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(aliceDetails, null, aliceDetails.getAuthorities()));
    }

    @Test
    void loginAuthenticatesChangesSessionIdAndStoresContext() {
        when(request.getSession(true)).thenReturn(session);
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(aliceDetails, null, aliceDetails.getAuthorities()));

        AuthUserResponse result = service.login(new LoginRequest("  Alice ", "secret123"), request, response);

        assertEquals(1L, result.id());
        assertEquals("alice", result.username());
        assertEquals("Alice", result.displayName());
        assertEquals(UserRole.REVIEWER, result.role());

        verify(authenticationManager).authenticate(
                argThat(token -> "alice".equals(((UsernamePasswordAuthenticationToken) token).getPrincipal())));
        verify(request).changeSessionId();
        ArgumentCaptor<SecurityContext> contextCaptor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(contextCaptor.capture(), eq(request), eq(response));
        assertEquals("alice", ((AppUserDetails) contextCaptor.getValue().getAuthentication().getPrincipal()).getUsername());
        assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(auditService).recordAs(eq("alice"), eq(AuditAction.LOGIN), eq("USER"), eq(1L), anyString());
    }

    @Test
    void loginWithBadCredentialsReturns401AndDoesNotCreateSession() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BizException e = assertThrows(BizException.class,
                () -> service.login(new LoginRequest("alice", "wrong"), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void loginWithDisabledAccountReturns401WithoutLeakingReason() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("disabled"));

        BizException e = assertThrows(BizException.class,
                () -> service.login(new LoginRequest("alice", "secret123"), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void meRequiresAuthentication() {
        BizException e = assertThrows(BizException.class, () -> service.me());
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void meReturnsCurrentPrincipal() {
        loginInContext();

        AuthUserResponse result = service.me();

        assertEquals(1L, result.id());
        assertEquals("alice", result.username());
        assertEquals(UserRole.REVIEWER, result.role());
    }

    @Test
    void logoutAuditsInvalidatesSessionAndClearsContext() {
        loginInContext();
        when(request.getSession(false)).thenReturn(session);

        service.logout(request);

        verify(auditService).recordAs(eq("alice"), eq(AuditAction.LOGOUT), eq("USER"), isNull(), anyString());
        verify(session).invalidate();
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void changePasswordValidatesOldPasswordAndStoresNewHash() {
        loginInContext();
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("oldpass1", alice.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("newpass12")).thenReturn("$2a$newhash");

        service.changePassword(new PasswordChangeRequest("oldpass1", "newpass12"));

        assertEquals("$2a$newhash", alice.getPasswordHash());
        verify(userRepository).save(alice);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        loginInContext();
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("wrongpass", alice.getPasswordHash())).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> service.changePassword(new PasswordChangeRequest("wrongpass", "newpass12")));

        assertTrue(e.getMessage().contains("原密码不正确"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePasswordRejectsTooShortNewPassword() {
        loginInContext();

        BizException e = assertThrows(BizException.class,
                () -> service.changePassword(new PasswordChangeRequest("oldpass1", "short")));

        assertTrue(e.getMessage().contains("至少 8 位"));
        verifyNoInteractions(userRepository, passwordEncoder);
    }
}
