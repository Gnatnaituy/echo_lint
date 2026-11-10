package com.echolint.service;

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
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 用户管理（ADMIN）：列表、新建、更新、禁用（不物理删除，保留审计引用）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final int MAX_USERNAME_LENGTH = 64;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SessionEpochService sessionEpochService;

    public List<UserView> list() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(UserView::from)
                .toList();
    }

    /** 菜单目录（ADMIN）：枚举顺序即展示顺序，供用户管理的菜单绑定勾选 */
    public List<MenuOptionView> menuOptions() {
        return MenuOptionView.catalog();
    }

    @Transactional
    public UserView create(UserCreateRequest request) {
        String username = normalizeUsername(request.username());
        if (!StringUtils.hasText(username)) {
            throw new BizException("用户名不能为空");
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new BizException("用户名长度不能超过 " + MAX_USERNAME_LENGTH + " 位");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BizException("用户名已存在: " + username, HttpStatus.CONFLICT);
        }
        validatePassword(request.password());
        UserRole role = parseRole(request.role(), UserRole.VIEWER);
        // 省略 menuKeys = 不限制；显式传入时严格校验（至少 1 项 / 未知 key / 角色越权 400）
        String menuKeys =
                request.menuKeys() == null
                        ? null
                        : MenuKey.toStored(MenuKey.parseSelection(request.menuKeys(), role), role);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(
                StringUtils.hasText(request.displayName())
                        ? request.displayName().trim()
                        : username);
        user.setRole(role);
        user.setEnabled(true);
        user.setMenuKeys(menuKeys);
        AppUser saved = userRepository.save(user);

        auditService.record(
                AuditAction.USER_CREATE,
                "USER",
                saved.getId(),
                "创建用户 "
                        + username
                        + "，角色 "
                        + role
                        + "，"
                        + MenuKey.describe(role, saved.getMenuKeys()));
        log.info("管理员创建用户 {}（role={}, menu={}）", username, role, saved.getMenuKeys());
        return UserView.from(saved);
    }

    @Transactional
    public UserView update(Long id, UserUpdateRequest request) {
        AppUser user = find(id);

        UserRole newRole =
                StringUtils.hasText(request.role())
                        ? parseRole(request.role(), user.getRole())
                        : user.getRole();
        boolean newEnabled = request.enabled() == null ? user.isEnabled() : request.enabled();
        boolean remainsEnabledAdmin = newRole == UserRole.ADMIN && newEnabled;
        ensureNotLastEnabledAdmin(user, remainsEnabledAdmin);

        // 菜单：显式传数组 = 按新角色严格校验后覆盖；省略 = 保留旧绑定（改角色时按新角色过滤旧绑定）
        String newMenuKeys =
                request.menuKeys() == null
                        ? MenuKey.rebindForRole(user.getMenuKeys(), newRole)
                        : MenuKey.toStored(
                                MenuKey.parseSelection(request.menuKeys(), newRole), newRole);
        ensureUsersMenuRemainsReachable(user, newRole, newEnabled, newMenuKeys);

        if (StringUtils.hasText(request.displayName())) {
            user.setDisplayName(request.displayName().trim());
        }
        user.setRole(newRole);
        user.setEnabled(newEnabled);
        user.setMenuKeys(newMenuKeys);
        if (StringUtils.hasText(request.password())) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        AppUser saved = userRepository.save(user);
        // 角色 / 启用状态 / 密码 / 菜单绑定任一变化后，让该用户的旧会话立即失效：
        // 会话里保存的是登录时刻的权限快照（含生效菜单），不 bump 会一直用到会话过期。
        // 说明：即便只改 displayName 也会 bump（保守策略，代价仅是该用户重登一次）；
        // 若事务随后回滚，最多是多失效一次（fail-closed，不影响数据正确性）。
        sessionEpochService.bump(saved.getUsername());

        auditService.record(
                AuditAction.USER_UPDATE,
                "USER",
                saved.getId(),
                "更新用户 "
                        + saved.getUsername()
                        + "，角色 "
                        + newRole
                        + "，启用="
                        + newEnabled
                        + "，"
                        + MenuKey.describe(newRole, saved.getMenuKeys())
                        + (StringUtils.hasText(request.password()) ? "，重置密码" : ""));
        log.info(
                "管理员更新用户 {}（role={}, enabled={}, menu={}）",
                saved.getUsername(),
                newRole,
                newEnabled,
                saved.getMenuKeys());
        return UserView.from(saved);
    }

    /** 禁用用户（保留记录，供审计/复核历史引用） */
    @Transactional
    public void disable(Long id) {
        AppUser user = find(id);
        ensureNotLastEnabledAdmin(user, false);
        if (!user.isEnabled()) {
            return;
        }
        ensureUsersMenuRemainsReachable(user, user.getRole(), false, user.getMenuKeys());
        user.setEnabled(false);
        userRepository.save(user);
        // 禁用后旧会话必须立即失效：账号被禁用不能等到会话自然过期
        sessionEpochService.bump(user.getUsername());
        auditService.record(AuditAction.USER_DISABLE, "USER", id, "禁用用户 " + user.getUsername());
        log.info("管理员禁用用户 {}", user.getUsername());
    }

    private AppUser find(Long id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> new BizException("用户不存在: " + id, HttpStatus.NOT_FOUND));
    }

    /** 不能禁用/降级最后一个仍启用的管理员（否则系统将无人可管理） */
    private void ensureNotLastEnabledAdmin(AppUser user, boolean remainsEnabledAdmin) {
        if (user.getRole() == UserRole.ADMIN
                && user.isEnabled()
                && !remainsEnabledAdmin
                && userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new BizException("不能禁用或降级最后一个启用的管理员", HttpStatus.CONFLICT);
        }
    }

    /**
     * 防「用户管理」入口锁死：若该用户当前是「启用且生效菜单含 USERS 的 ADMIN」，而本次更新/DELETE 后不再是（收窄掉菜单 / 降级 / 禁用），且不存在其他这样的账号，则
     * 409。
     *
     * <p>菜单绑定本身不改变 URL 权限矩阵，这里按产品约定做保守拦截：至少保留一个界面上能以管理员身份 进入用户管理的账号。
     */
    private void ensureUsersMenuRemainsReachable(
            AppUser user, UserRole newRole, boolean newEnabled, String newMenuKeys) {
        if (!isEnabledAdminWithUsersMenu(user)) {
            return;
        }
        if (newRole == UserRole.ADMIN
                && newEnabled
                && MenuKey.effective(newRole, newMenuKeys).contains(MenuKey.USERS)) {
            return;
        }
        if (otherEnabledAdminWithUsersMenuExists(user)) {
            return;
        }
        throw new BizException("至少保留一个可访问「用户管理」的启用管理员", HttpStatus.CONFLICT);
    }

    private boolean otherEnabledAdminWithUsersMenuExists(AppUser user) {
        return userRepository.findByRoleAndEnabledTrue(UserRole.ADMIN).stream()
                .filter(other -> !Objects.equals(other.getId(), user.getId()))
                .anyMatch(this::isEnabledAdminWithUsersMenu);
    }

    private boolean isEnabledAdminWithUsersMenu(AppUser user) {
        return user.getRole() == UserRole.ADMIN
                && user.isEnabled()
                && MenuKey.effective(user.getRole(), user.getMenuKeys()).contains(MenuKey.USERS);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new BizException("密码长度不能超过 " + MAX_PASSWORD_LENGTH + " 位");
        }
    }

    private static UserRole parseRole(String raw, UserRole fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException("未知角色: " + raw + "（仅支持 ADMIN / REVIEWER / VIEWER）");
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
