package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.echolint.domain.AuditAction;
import com.echolint.domain.MenuKey;
import com.echolint.domain.UserRole;
import com.echolint.dto.MenuOptionView;
import com.echolint.dto.UserCreateRequest;
import com.echolint.dto.UserUpdateRequest;
import com.echolint.dto.UserView;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.security.SessionEpochService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 用户管理：用户名规范化/唯一、密码策略、最后一个管理员、菜单绑定校验与防锁死。 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private AppUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private SessionEpochService sessionEpochService;

    private UserService service;

    @BeforeEach
    void setUp() {
        service =
                new UserService(userRepository, passwordEncoder, auditService, sessionEpochService);
    }

    private static AppUser user(
            Long id, String username, UserRole role, boolean enabled, String menuKeys) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setDisplayName(username);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setMenuKeys(menuKeys);
        return user;
    }

    private AppUser admin(boolean enabled) {
        return user(1L, "admin", UserRole.ADMIN, enabled, null);
    }

    private static List<String> menuNames(UserRole role) {
        return MenuKey.allowedFor(role).stream().map(MenuKey::name).toList();
    }

    private void stubSavedUser() {
        when(userRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------- 创建用户

    @Test
    void createNormalizesUsernameHashesPasswordAndAudits() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("bcrypt-hash");
        stubSavedUser();

        UserView view =
                service.create(
                        new UserCreateRequest("  Alice ", "password1", null, "reviewer", null));

        assertEquals("alice", view.username());
        assertEquals("alice", view.displayName(), "未填显示名时回退为用户名");
        assertEquals(UserRole.REVIEWER, view.role());
        assertTrue(view.enabled());
        // 未指定菜单 = 不限制：生效菜单即角色允许的全部菜单
        assertEquals(menuNames(UserRole.REVIEWER), view.menuKeys());
        assertFalse(view.menuRestricted());
        verify(auditService)
                .record(eq(AuditAction.USER_CREATE), eq("USER"), any(), contains("菜单=全部"));
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "alice", "password1", "Alice", "VIEWER", null)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsTooShortPassword() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "alice", "short", "Alice", "VIEWER", null)));

        assertTrue(e.getMessage().contains("至少 8 位"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createStoresRestrictedMenuBinding() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("bcrypt-hash");
        stubSavedUser();

        UserView view =
                service.create(
                        new UserCreateRequest(
                                "alice",
                                "password1",
                                null,
                                "VIEWER",
                                List.of("REPORTS", "DASHBOARD")));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        // 去重 + 枚举顺序落库
        assertEquals("DASHBOARD,REPORTS", captor.getValue().getMenuKeys());
        assertEquals(List.of("DASHBOARD", "REPORTS"), view.menuKeys());
        assertTrue(view.menuRestricted());
        verify(auditService)
                .record(eq(AuditAction.USER_CREATE), eq("USER"), any(), contains("菜单=2 项"));
    }

    @Test
    void createFoldsFullMenuSelectionToUnrestricted() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("bcrypt-hash");
        stubSavedUser();

        UserView view =
                service.create(
                        new UserCreateRequest(
                                "alice",
                                "password1",
                                null,
                                "REVIEWER",
                                menuNames(UserRole.REVIEWER)));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertNull(captor.getValue().getMenuKeys(), "全选等价于不限制，应存 null");
        assertEquals(menuNames(UserRole.REVIEWER), view.menuKeys());
        assertFalse(view.menuRestricted());
    }

    @Test
    void createRejectsUnknownMenu() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "alice",
                                                "password1",
                                                null,
                                                "VIEWER",
                                                List.of("NOT_A_MENU"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("未知菜单: NOT_A_MENU", e.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsAdminOnlyMenuForNonAdmin() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "alice",
                                                "password1",
                                                null,
                                                "VIEWER",
                                                List.of("COSTS"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("不可绑定菜单: COSTS"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createRejectsEmptyMenuSelection() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "alice", "password1", null, "ADMIN", List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("至少"));
        verify(userRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- 更新用户 / 菜单

    @Test
    void updateKeepsMenuBindingWhenFieldOmitted() {
        AppUser bob = user(2L, "bob", UserRole.VIEWER, true, "DASHBOARD");
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        stubSavedUser();

        UserView view = service.update(2L, new UserUpdateRequest("新名字", null, null, null, null));

        assertEquals("DASHBOARD", bob.getMenuKeys());
        assertEquals(List.of("DASHBOARD"), view.menuKeys());
        assertTrue(view.menuRestricted());
        assertEquals("新名字", view.displayName());
    }

    @Test
    void updateCanRestoreUnrestrictedBySelectingAllAllowedMenus() {
        AppUser bob = user(2L, "bob", UserRole.VIEWER, true, "DASHBOARD");
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        stubSavedUser();

        UserView view =
                service.update(
                        2L,
                        new UserUpdateRequest(null, null, null, null, menuNames(UserRole.VIEWER)));

        assertNull(bob.getMenuKeys(), "全选应折叠为 null（不限制）");
        assertEquals(menuNames(UserRole.VIEWER), view.menuKeys());
        assertFalse(view.menuRestricted());
    }

    @Test
    void updateFiltersOldBindingWhenRoleChanges() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,COSTS");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        stubSavedUser();

        UserView view =
                service.update(1L, new UserUpdateRequest(null, "REVIEWER", true, null, null));

        assertEquals(UserRole.REVIEWER, view.role());
        // 旧绑定按新角色过滤：COSTS 是 ADMIN-only，被裁掉
        assertEquals("DASHBOARD", admin.getMenuKeys());
        assertEquals(List.of("DASHBOARD"), view.menuKeys());
        assertTrue(view.menuRestricted());
    }

    @Test
    void updateFoldsOldBindingToNullWhenRoleChangeMakesItRedundant() {
        String allNonAdmin = String.join(",", menuNames(UserRole.VIEWER));
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, allNonAdmin);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        stubSavedUser();

        UserView view = service.update(1L, new UserUpdateRequest(null, "VIEWER", true, null, null));

        assertNull(admin.getMenuKeys(), "过滤后覆盖新角色全部菜单 → 折叠为不限制");
        assertEquals(menuNames(UserRole.VIEWER), view.menuKeys());
        assertFalse(view.menuRestricted());
    }

    // ---------------------------------------------------------------- 最后一个管理员

    @Test
    void cannotDemoteLastEnabledAdmin() {
        AppUser lastAdmin = admin(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        1L,
                                        new UserUpdateRequest(null, "VIEWER", null, null, null)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("最后一个"));
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void cannotDisableLastEnabledAdmin() {
        AppUser lastAdmin = admin(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.disable(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void canDemoteAdminWhenAnotherEnabledAdminExists() {
        AppUser admin = admin(true);
        AppUser otherAdmin = user(2L, "root2", UserRole.ADMIN, true, null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, otherAdmin));
        stubSavedUser();

        UserView view =
                service.update(1L, new UserUpdateRequest("新名字", "REVIEWER", true, null, null));

        assertEquals(UserRole.REVIEWER, view.role());
        assertEquals("新名字", view.displayName());
        verify(auditService).record(eq(AuditAction.USER_UPDATE), eq("USER"), any(), anyString());
        // 角色变化后旧会话应立即失效（会话里是登录时刻的权限快照）
        verify(sessionEpochService).bump("admin");
    }

    @Test
    void disableKeepsUserButMarksDisabled() {
        AppUser reviewer = user(2L, "bob", UserRole.REVIEWER, true, null);
        when(userRepository.findById(2L)).thenReturn(Optional.of(reviewer));
        stubSavedUser();

        service.disable(2L);

        assertFalse(reviewer.isEnabled(), "禁用不应物理删除用户");
        verify(userRepository, never()).delete(any());
        verify(auditService).record(eq(AuditAction.USER_DISABLE), eq("USER"), eq(2L), anyString());
        // 禁用后旧会话应立即失效，不能等会话自然过期
        verify(sessionEpochService).bump("bob");
    }

    // ---------------------------------------------------------------- 防「用户管理」锁死

    @Test
    void cannotNarrowUsersMenuOfLastUsersCapableAdmin() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,USERS");
        AppUser ops = user(2L, "ops", UserRole.ADMIN, true, "DASHBOARD");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, ops));

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        1L,
                                        new UserUpdateRequest(
                                                null, null, null, null, List.of("DASHBOARD"))));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("用户管理"));
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void canNarrowUsersMenuWhenAnotherUsersCapableAdminExists() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,USERS");
        AppUser otherAdmin = user(2L, "root2", UserRole.ADMIN, true, null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, otherAdmin));
        stubSavedUser();

        UserView view =
                service.update(
                        1L, new UserUpdateRequest(null, null, null, null, List.of("DASHBOARD")));

        assertEquals("DASHBOARD", admin.getMenuKeys());
        assertFalse(view.menuKeys().contains("USERS"));
        assertTrue(view.menuRestricted());
    }

    @Test
    void cannotDemoteLastUsersCapableAdmin() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,USERS");
        AppUser ops = user(2L, "ops", UserRole.ADMIN, true, "DASHBOARD");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, ops));

        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        1L,
                                        new UserUpdateRequest(null, "VIEWER", true, null, null)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("至少保留一个可访问「用户管理」的启用管理员"));
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void cannotDisableLastUsersCapableAdmin() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,USERS");
        AppUser ops = user(2L, "ops", UserRole.ADMIN, true, "DASHBOARD");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, ops));

        BizException e = assertThrows(BizException.class, () -> service.disable(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("至少保留一个可访问「用户管理」的启用管理员"));
        verify(userRepository, never()).save(any());
        verify(sessionEpochService, never()).bump(anyString());
    }

    @Test
    void canDemoteUsersCapableAdminWhenAnotherExists() {
        AppUser admin = user(1L, "admin", UserRole.ADMIN, true, "DASHBOARD,USERS");
        AppUser otherAdmin = user(2L, "root2", UserRole.ADMIN, true, null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);
        when(userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenReturn(List.of(admin, otherAdmin));
        stubSavedUser();

        UserView view = service.update(1L, new UserUpdateRequest(null, "VIEWER", true, null, null));

        assertEquals(UserRole.VIEWER, view.role());
        assertEquals("DASHBOARD", admin.getMenuKeys());
        assertEquals(List.of("DASHBOARD"), view.menuKeys());
    }

    // ---------------------------------------------------------------- 菜单目录

    @Test
    void menuOptionsExposeCatalogInEnumOrder() {
        List<MenuOptionView> options = service.menuOptions();

        assertEquals(MenuKey.values().length, options.size());
        assertEquals("DASHBOARD", options.get(0).key());
        assertEquals("/", options.get(0).path());
        assertFalse(options.get(0).adminOnly());
        assertTrue(
                options.stream()
                        .anyMatch(option -> "USERS".equals(option.key()) && option.adminOnly()));
        assertEquals("AUDIT", options.get(options.size() - 1).key());
        assertTrue(options.get(options.size() - 1).adminOnly());
    }
}
