package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.echolint.domain.AuditAction;
import com.echolint.domain.UserRole;
import com.echolint.dto.AuthUserResponse;
import com.echolint.dto.LoginRequest;
import com.echolint.dto.PasswordChangeRequest;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.security.AppUserDetails;
import com.echolint.security.LoginAttemptService;
import com.echolint.security.SessionEpochService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
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

/** 会话认证：登录（校验 + 换 sessionId + 写入 SecurityContext）、失败 401、登出、改密。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private AppUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SecurityContextRepository securityContextRepository;
    @Mock private AuditService auditService;
    @Mock private SessionEpochService sessionEpochService;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private HttpSession session;

    private AuthService service;
    private AppUser alice;
    private AppUserDetails aliceDetails;

    @BeforeEach
    void setUp() {
        service =
                new AuthService(
                        authenticationManager,
                        userRepository,
                        passwordEncoder,
                        securityContextRepository,
                        auditService,
                        sessionEpochService,
                        loginAttemptService);

        alice = new AppUser();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setPasswordHash("$2a$hashed");
        alice.setDisplayName("Alice");
        alice.setRole(UserRole.REVIEWER);
        alice.setEnabled(true);
        // 菜单绑定：读路径宽松（COSTS 对 REVIEWER 不可用被跳过）+ 返回按枚举顺序
        alice.setMenuKeys("REPORTS,DASHBOARD,COSTS");
        aliceDetails = new AppUserDetails(alice);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginInContext() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                aliceDetails, null, aliceDetails.getAuthorities()));
    }

    @Test
    void loginAuthenticatesChangesSessionIdAndStoresContext() {
        when(request.getSession(true)).thenReturn(session);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(sessionEpochService.current("alice")).thenReturn(7L);
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(
                        new UsernamePasswordAuthenticationToken(
                                aliceDetails, null, aliceDetails.getAuthorities()));

        AuthUserResponse result =
                service.login(new LoginRequest("  Alice ", "secret123"), request, response);

        assertEquals(1L, result.id());
        assertEquals("alice", result.username());
        assertEquals("Alice", result.displayName());
        assertEquals(UserRole.REVIEWER, result.role());
        // 登录响应带上登录时刻的生效菜单（枚举顺序，角色不可用项已收窄掉）
        assertEquals(List.of("DASHBOARD", "REPORTS"), result.menuKeys());

        verify(authenticationManager)
                .authenticate(
                        argThat(
                                token ->
                                        "alice"
                                                .equals(
                                                        ((UsernamePasswordAuthenticationToken)
                                                                        token)
                                                                .getPrincipal())));
        verify(request).changeSessionId();
        ArgumentCaptor<SecurityContext> contextCaptor =
                ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository)
                .saveContext(contextCaptor.capture(), eq(request), eq(response));
        assertEquals(
                "alice",
                ((AppUserDetails) contextCaptor.getValue().getAuthentication().getPrincipal())
                        .getUsername());
        assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
        // 登录时把当前会话纪元写入 session，供 UserSessionGuardFilter 后续比对
        ArgumentCaptor<Object> epochCaptor = ArgumentCaptor.forClass(Object.class);
        verify(session)
                .setAttribute(eq(SessionEpochService.SESSION_ATTRIBUTE), epochCaptor.capture());
        assertEquals(7L, ((Number) epochCaptor.getValue()).longValue());
        // 成功登录清零该 username+ip 的失败计数
        verify(loginAttemptService).reset("alice", "127.0.0.1");
        verify(auditService)
                .recordAs(eq("alice"), eq(AuditAction.LOGIN), eq("USER"), eq(1L), anyString());
    }

    @Test
    void loginWithBadCredentialsReturns401AndDoesNotCreateSession() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BizException e =
                assertThrows(
                        BizException.class,
                        () -> service.login(new LoginRequest("alice", "wrong"), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        // 失败计数 + LOGIN_FAILED 审计（审计 detail 含 IP，但不含密码）
        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verify(loginAttemptService, never()).reset(anyString(), anyString());
        verify(auditService)
                .recordAs(
                        eq("alice"),
                        eq(AuditAction.LOGIN_FAILED),
                        eq("USER"),
                        isNull(),
                        argThat(
                                detail ->
                                        detail.contains("127.0.0.1") && !detail.contains("wrong")));
    }

    @Test
    void loginWithDisabledAccountReturns401WithoutLeakingReason() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("disabled"));

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.login(
                                        new LoginRequest("alice", "secret123"), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertEquals("用户名或密码错误", e.getMessage());
        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
    }

    @Test
    void loginIsRejectedWith429BeforeAuthenticatingWhenAlreadyBlocked() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("alice", "127.0.0.1")).thenReturn(true);
        when(loginAttemptService.retryAfterSeconds("alice", "127.0.0.1")).thenReturn(42L);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.login(
                                        new LoginRequest("alice", "whatever"), request, response));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertTrue(e.getMessage().contains("42"));
        verify(response).setHeader("Retry-After", "42");
        verify(authenticationManager, never()).authenticate(any());
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void loginReturns429WhenThisFailureReachesTheLimit() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        // 第一次（登录前）未封禁；记录本次失败后已达阈值
        when(loginAttemptService.isBlocked("alice", "127.0.0.1")).thenReturn(false, true);
        when(loginAttemptService.retryAfterSeconds("alice", "127.0.0.1")).thenReturn(12L);
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BizException e =
                assertThrows(
                        BizException.class,
                        () -> service.login(new LoginRequest("alice", "wrong"), request, response));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verify(response).setHeader("Retry-After", "12");
        verify(loginAttemptService, never()).reset(anyString(), anyString());
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
        assertEquals(List.of("DASHBOARD", "REPORTS"), result.menuKeys());
    }

    @Test
    void logoutAuditsInvalidatesSessionAndClearsContext() {
        loginInContext();
        when(request.getSession(false)).thenReturn(session);

        service.logout(request);

        verify(auditService)
                .recordAs(eq("alice"), eq(AuditAction.LOGOUT), eq("USER"), isNull(), anyString());
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
        // 改密后 bump 会话纪元：该用户所有旧会话（含当前会话）下一次请求即失效
        verify(sessionEpochService).bump("alice");
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        loginInContext();
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("wrongpass", alice.getPasswordHash())).thenReturn(false);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.changePassword(
                                        new PasswordChangeRequest("wrongpass", "newpass12")));

        assertTrue(e.getMessage().contains("原密码不正确"));
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void changePasswordRejectsTooShortNewPassword() {
        loginInContext();

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.changePassword(
                                        new PasswordChangeRequest("oldpass1", "short")));

        assertTrue(e.getMessage().contains("至少 8 位"));
        verifyNoInteractions(userRepository, passwordEncoder, sessionEpochService);
    }
}
