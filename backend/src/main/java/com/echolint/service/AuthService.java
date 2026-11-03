package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.dto.AuthUserResponse;
import com.echolint.dto.LoginRequest;
import com.echolint.dto.PasswordChangeRequest;
import com.echolint.entity.AppUser;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.security.AppUserDetails;
import com.echolint.security.LoginAttemptService;
import com.echolint.security.SecurityUtils;
import com.echolint.security.SessionEpochService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Objects;
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

/** 会话认证：登录（手动把 SecurityContext 写入 HttpSession）、登出、当前用户、修改密码。 */
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
    private final SessionEpochService sessionEpochService;
    private final LoginAttemptService loginAttemptService;

    /**
     * 用户名密码登录，成功后会话 cookie 即登录态（HttpOnly + SameSite=Lax）。 凭据错误 / 账号禁用统一返回 401，不区分原因以避免账号枚举。
     *
     * <p>失败限速（审计 P2-7）：按 username+IP 计数，5 分钟内失败 5 次后返回 429 + {@code Retry-After}，成功登录清零；每次失败写
     * {@code LOGIN_FAILED} 审计（actor=用户名、 detail 含 IP，绝不含密码）。
     */
    public AuthUserResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String username = normalizeUsername(request.username());
        String ip = clientIp(httpRequest);
        // 已封禁：直接 429，避免继续消耗 BCrypt 校验开销
        if (loginAttemptService.isBlocked(username, ip)) {
            throw tooManyAttempts(username, ip, httpResponse);
        }
        Authentication authentication;
        try {
            authentication =
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(username, ip);
            auditService.recordAs(
                    username, AuditAction.LOGIN_FAILED, "USER", null, "登录失败（IP " + ip + "）");
            log.info("登录失败：username={}, ip={}", username, ip);
            // 本次失败后刚好达到阈值：返回 429（带 Retry-After），否则保持 401
            if (loginAttemptService.isBlocked(username, ip)) {
                throw tooManyAttempts(username, ip, httpResponse);
            }
            throw new BizException("用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        loginAttemptService.reset(username, ip);

        // 防会话固定：登录前可能已存在会话，先确保会话存在再换新 id
        HttpSession session = httpRequest.getSession(true);
        httpRequest.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        AppUserDetails user = (AppUserDetails) authentication.getPrincipal();
        // 记录登录时刻的会话纪元：之后账号被禁用/降级/改密 bump 纪元，本会话会在下一次请求被过滤器判为失效
        session.setAttribute(
                SessionEpochService.SESSION_ATTRIBUTE,
                sessionEpochService.current(user.getUsername()));
        auditService.recordAs(user.getUsername(), AuditAction.LOGIN, "USER", user.getId(), "登录成功");
        log.info("用户 {} 登录成功（role={}）", user.getUsername(), user.getRole());
        return AuthUserResponse.from(user);
    }

    /** 构造 429 响应：设置 Retry-After 头（供前端/网关退避），消息里给出剩余秒数 */
    private BizException tooManyAttempts(
            String username, String ip, HttpServletResponse httpResponse) {
        long retryAfter = Math.max(1L, loginAttemptService.retryAfterSeconds(username, ip));
        httpResponse.setHeader("Retry-After", String.valueOf(retryAfter));
        return new BizException("登录失败次数过多，请 " + retryAfter + " 秒后重试", HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 限速维度用的客户端 IP：只取连接地址，不信任可伪造的 X-Forwarded-For */
    private static String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.trim();
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
        if (!passwordEncoder.matches(
                Objects.requireNonNullElse(request.oldPassword(), ""), user.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        // 改密后让该用户所有旧会话立即失效（包括当前会话：前端收到 401 后会引导重新登录，属预期行为）
        sessionEpochService.bump(user.getUsername());
        log.info("用户 {} 修改密码成功", user.getUsername());
    }

    private AppUser findCurrentUser(AppUserDetails current) {
        if (current.getId() != null) {
            return userRepository
                    .findById(current.getId())
                    .orElseThrow(() -> new BizException("用户不存在", HttpStatus.UNAUTHORIZED));
        }
        return userRepository
                .findByUsername(current.getUsername())
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
