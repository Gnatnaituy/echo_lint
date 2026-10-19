package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.domain.UserRole;
import com.echolint.dto.UserCreateRequest;
import com.echolint.dto.UserUpdateRequest;
import com.echolint.dto.UserView;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 用户管理（ADMIN）：列表、新建、更新、禁用（不物理删除，保留审计引用）。
 */
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

    public List<UserView> list() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(UserView::from)
                .toList();
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

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(StringUtils.hasText(request.displayName())
                ? request.displayName().trim()
                : username);
        user.setRole(role);
        user.setEnabled(true);
        AppUser saved = userRepository.save(user);

        auditService.record(AuditAction.USER_CREATE, "USER", saved.getId(),
                "创建用户 " + username + "，角色 " + role);
        log.info("管理员创建用户 {}（role={}）", username, role);
        return UserView.from(saved);
    }

    @Transactional
    public UserView update(Long id, UserUpdateRequest request) {
        AppUser user = find(id);

        UserRole newRole = StringUtils.hasText(request.role()) ? parseRole(request.role(), user.getRole()) : user.getRole();
        boolean newEnabled = request.enabled() == null ? user.isEnabled() : request.enabled();
        boolean remainsEnabledAdmin = newRole == UserRole.ADMIN && newEnabled;
        ensureNotLastEnabledAdmin(user, remainsEnabledAdmin);

        if (StringUtils.hasText(request.displayName())) {
            user.setDisplayName(request.displayName().trim());
        }
        user.setRole(newRole);
        user.setEnabled(newEnabled);
        if (StringUtils.hasText(request.password())) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        AppUser saved = userRepository.save(user);

        auditService.record(AuditAction.USER_UPDATE, "USER", saved.getId(),
                "更新用户 " + saved.getUsername() + "，角色 " + newRole + "，启用=" + newEnabled
                        + (StringUtils.hasText(request.password()) ? "，重置密码" : ""));
        log.info("管理员更新用户 {}（role={}, enabled={}）", saved.getUsername(), newRole, newEnabled);
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
        user.setEnabled(false);
        userRepository.save(user);
        auditService.record(AuditAction.USER_DISABLE, "USER", id, "禁用用户 " + user.getUsername());
        log.info("管理员禁用用户 {}", user.getUsername());
    }

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException("用户不存在: " + id, HttpStatus.NOT_FOUND));
    }

    /** 不能禁用/降级最后一个仍启用的管理员（否则系统将无人可管理） */
    private void ensureNotLastEnabledAdmin(AppUser user, boolean remainsEnabledAdmin) {
        if (user.getRole() == UserRole.ADMIN && user.isEnabled() && !remainsEnabledAdmin
                && userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new BizException("不能禁用或降级最后一个启用的管理员", HttpStatus.CONFLICT);
        }
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
