package com.echolint.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会话纪元校验过滤器：账号被禁用 / 降级 / 改密后，旧会话在下一次请求时立即失效（返回 401）。
 *
 * <p>为什么不用 Spring Security {@code SessionRegistry}：本项目登录由 {@code AuthController} 手动完成， 不经过 {@code
 * SessionAuthenticationStrategy}，会话并发/过期管理接入成本高且容易漏； 采用「session 存纪元 + 每请求比对内存值」的最小方案，与单实例内存会话语义一致。
 *
 * <p>行为：
 *
 * <ul>
 *   <li>只处理 {@code /api/**} 与 {@code /uploads/**}（录音音频流同样受登录态保护）；
 *   <li>匿名请求、API Key 请求（principal 不是 {@link AppUserDetails}）直接放行，交给后续鉴权；
 *   <li>登录/SSO 公开配置是匿名入口，不参与校验，避免旧 cookie 挡住重新登录；
 *   <li>纪元不匹配或缺失：清空 SecurityContext、销毁会话，并复用 {@link JsonAuthenticationEntryPoint} 输出与其它未登录场景一致的 401
 *       JSON。
 * </ul>
 *
 * <p>不注册为 Spring Bean：由 {@code SecurityConfig} 显式加入安全过滤器链（与 {@code ApiKeyAuthFilter} 同款）， 避免被
 * Spring Boot 再注册一次到 Servlet 过滤器链导致重复执行。
 */
@Slf4j
@RequiredArgsConstructor
public class UserSessionGuardFilter extends OncePerRequestFilter {

    private final SessionEpochService epochService;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        boolean sessionProtected = uri.startsWith("/api/") || uri.startsWith("/uploads/");
        if (!sessionProtected) {
            return true;
        }
        // 匿名入口（登录、登录页 SSO 配置）：本身不依赖会话，不能被旧 cookie 挡住
        return "/api/auth/login".equals(uri) || "/api/auth/sso/config".equals(uri);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserDetails user)) {
            // 匿名 / API Key / 其它 principal：由后续授权规则决定 401 或放行
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            // 会话已过期/不存在：交由后续鉴权按未登录处理
            filterChain.doFilter(request, response);
            return;
        }

        Object rawEpoch = session.getAttribute(SessionEpochService.SESSION_ATTRIBUTE);
        Long sessionEpoch = rawEpoch instanceof Number number ? number.longValue() : null;
        long currentEpoch = epochService.current(user.getUsername());
        if (sessionEpoch == null || sessionEpoch.longValue() != currentEpoch) {
            log.info(
                    "会话纪元不匹配，强制失效：username={} sessionEpoch={} currentEpoch={}",
                    user.getUsername(),
                    sessionEpoch,
                    currentEpoch);
            SecurityContextHolder.clearContext();
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 会话已被并发请求销毁，属预期
            }
            authenticationEntryPoint.commence(
                    request, response, new InsufficientAuthenticationException("会话已失效，请重新登录"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
