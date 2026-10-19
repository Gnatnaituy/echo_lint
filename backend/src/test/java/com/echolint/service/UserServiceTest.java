package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.domain.UserRole;
import com.echolint.dto.UserCreateRequest;
import com.echolint.dto.UserUpdateRequest;
import com.echolint.dto.UserView;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 用户管理：用户名规范化/唯一、密码策略、最后一个管理员不可禁用/降级。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, passwordEncoder, auditService);
    }

    private AppUser admin(boolean enabled) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("hash");
        user.setDisplayName("管理员");
        user.setRole(UserRole.ADMIN);
        user.setEnabled(enabled);
        return user;
    }

    @Test
    void createNormalizesUsernameHashesPasswordAndAudits() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserView view = service.create(new UserCreateRequest("  Alice ", "password1", null, "reviewer"));

        assertEquals("alice", view.username());
        assertEquals("alice", view.displayName(), "未填显示名时回退为用户名");
        assertEquals(UserRole.REVIEWER, view.role());
        assertTrue(view.enabled());
        verify(auditService).record(eq(AuditAction.USER_CREATE), eq("USER"), any(), anyString());
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new UserCreateRequest("alice", "password1", "Alice", "VIEWER")));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsTooShortPassword() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new UserCreateRequest("alice", "short", "Alice", "VIEWER")));

        assertTrue(e.getMessage().contains("至少 8 位"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void cannotDemoteLastEnabledAdmin() {
        AppUser lastAdmin = admin(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service.update(1L, new UserUpdateRequest(null, "VIEWER", null, null)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("最后一个"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void cannotDisableLastEnabledAdmin() {
        AppUser lastAdmin = admin(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.disable(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    void canDemoteAdminWhenAnotherEnabledAdminExists() {
        AppUser admin = admin(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserView view = service.update(1L, new UserUpdateRequest("新名字", "REVIEWER", true, null));

        assertEquals(UserRole.REVIEWER, view.role());
        assertEquals("新名字", view.displayName());
        verify(auditService).record(eq(AuditAction.USER_UPDATE), eq("USER"), any(), anyString());
    }

    @Test
    void disableKeepsUserButMarksDisabled() {
        AppUser reviewer = new AppUser();
        reviewer.setId(2L);
        reviewer.setUsername("bob");
        reviewer.setPasswordHash("hash");
        reviewer.setRole(UserRole.REVIEWER);
        reviewer.setEnabled(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(reviewer));
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.disable(2L);

        assertFalse(reviewer.isEnabled(), "禁用不应物理删除用户");
        verify(userRepository, never()).delete(any());
        verify(auditService).record(eq(AuditAction.USER_DISABLE), eq("USER"), eq(2L), anyString());
    }
}
