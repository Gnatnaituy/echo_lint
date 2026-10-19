package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.dto.AuthUserResponse;
import com.echolint.dto.LoginRequest;
import com.echolint.dto.PasswordChangeRequest;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.security.AppUserDetails;
import com.echolint.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/**
 * 会话认证：登录（手动把 SecurityContext 写入 HttpSession）、登出、当前用户、修改密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72; // BCrypt 有效输入上限

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final AuditService auditService;

    /**
     * 用户名密码登录，成功后会话 cookie 即登录态（HttpOnly + SameSite=Lax）。
     * 凭据错误 / 账号禁用统一返回 401，不区分原因以避免账号枚举。
     */
    public AuthUserResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String username = normalizeUsername(request.username());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException e) {
            log.info("登录失败：username={}", username);
            throw new BizException("用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }

        // 防会话固定：登录前可能已存在会话，先确保会话存在再换新 id
        httpRequest.getSession(true);
        httpRequest.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        AppUserDetails user = (AppUserDetails) authentication.getPrincipal();
        auditService.recordAs(user.getUsername(), AuditAction.LOGIN, "USER", user.getId(), "登录成功");
        log.info("用户 {} 登录成功（role={}）", user.getUsername(), user.getRole());
        return AuthUserResponse.from(user);
    }

    /** 当前登录用户；会话失效/未登录返回 401 */
    public AuthUserResponse me() {
        return AuthUserResponse.from(requireCurrentUser());
    }

    /** 注销当前会话（审计后再清理 SecurityContext 与 session） */
    public void logout(HttpServletRequest httpRequest) {
        String actor = SecurityUtils.currentUsername().orElse(null);
        if (actor != null) {
            auditService.recordAs(actor, AuditAction.LOGOUT, "USER", null, "退出登录");
        }
        SecurityContextHolder.clearContext();
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        log.info("用户 {} 已退出登录", actor == null ? "anonymous" : actor);
    }

    /** 修改当前用户密码；原密码错误返回 400，新密码不足 8 位返回 400 */
    @Transactional
    public void changePassword(PasswordChangeRequest request) {
        AppUserDetails current = requireCurrentUser();
        String newPassword = request.newPassword() == null ? "" : request.newPassword();
        validateNewPassword(newPassword);

        AppUser user = findCurrentUser(current);
        if (!passwordEncoder.matches(Objects.requireNonNullElse(request.oldPassword(), ""), user.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("用户 {} 修改密码成功", user.getUsername());
    }

    private AppUser findCurrentUser(AppUserDetails current) {
        if (current.getId() != null) {
            return userRepository.findById(current.getId())
                    .orElseThrow(() -> new BizException("用户不存在", HttpStatus.UNAUTHORIZED));
        }
        return userRepository.findByUsername(current.getUsername())
                .orElseThrow(() -> new BizException("用户不存在", HttpStatus.UNAUTHORIZED));
    }

    private AppUserDetails requireCurrentUser() {
        return SecurityUtils.currentUser()
                .orElseThrow(() -> new BizException("未登录或登录已过期", HttpStatus.UNAUTHORIZED));
    }

    private void validateNewPassword(String newPassword) {
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException("新密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new BizException("新密码长度不能超过 " + MAX_PASSWORD_LENGTH + " 位");
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
