package com.echolint.integration.tenant;

import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 租户解析过滤器（Batch 5）：作用于 {@code /api/*}。
 *
 * <ul>
 *   <li>设置 {@link TenantContext}（单租户固定 default，HEADER 模式取请求头）；</li>
 *   <li>解析失败（HEADER 模式缺失/非法）输出与全局异常处理一致的 JSON 400；</li>
 *   <li>请求结束（含异常）必定清理 ThreadLocal，避免线程复用串号；</li>
 *   <li>OPTIONS 预检直接放行（浏览器预检不会带自定义租户头）。</li>
 * </ul>
 *
 * <p>由 {@code IntegrationConfig} 以 {@code FilterRegistrationBean} 注册到 Spring Security 之前，
 * 保证租户校验结果与登录态无关、稳定返回 400。不注册为 Spring Bean，避免被 Boot 重复注册。</p>
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantResolver tenantResolver;
    private final ObjectMapper objectMapper;

    public TenantFilter(TenantResolver tenantResolver, ObjectMapper objectMapper) {
        this.tenantResolver = tenantResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId;
        try {
            tenantId = tenantResolver.resolve(request);
        } catch (BizException e) {
            writeBadRequest(response, e.getMessage());
            return;
        }
        if (!StringUtils.hasText(tenantId)) {
            writeBadRequest(response, "租户解析失败");
            return;
        }
        TenantContext.set(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", HttpServletResponse.SC_BAD_REQUEST,
                "message", message == null ? "租户校验失败" : message,
                "timestamp", LocalDateTime.now().toString()));
    }
}
