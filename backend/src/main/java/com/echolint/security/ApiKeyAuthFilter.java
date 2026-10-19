package com.echolint.security;

import com.echolint.service.ApiKeyService;
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
import java.util.Optional;

/**
 * 开放 API 鉴权过滤器（Batch 4a）：仅作用于 {@code /api/open/**}。
 *
 * <p>SecurityConfig 已放行 {@code /api/open/**}（不依赖会话），本过滤器是唯一的访问门禁：
 * 读取 {@code X-API-Key}，按 SHA-256 查启用中的密钥；缺失/无效/停用统一返回 JSON 401。
 * 通过后把 {@link ApiKeyService.ApiKeyContext} 写入 request attribute，供控制器审计使用。</p>
 *
 * <p>不注册为 Spring Bean：由 SecurityConfig 显式加入安全过滤器链，避免被 Boot 再注册一次
 * 到 Servlet 过滤器链导致重复执行。</p>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String ATTR_CONTEXT = "echolint.apiKeyContext";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/open/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // CORS 预检不带自定义头，放行给 CORS 处理器
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String rawKey = request.getHeader(HEADER);
        if (!StringUtils.hasText(rawKey)) {
            unauthorized(response, "缺少 X-API-Key 请求头");
            return;
        }
        Optional<ApiKeyService.ApiKeyContext> context = apiKeyService.authenticate(rawKey);
        if (context.isEmpty()) {
            unauthorized(response, "API Key 无效或已停用");
            return;
        }
        request.setAttribute(ATTR_CONTEXT, context.get());
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", HttpServletResponse.SC_UNAUTHORIZED,
                "message", message,
                "timestamp", LocalDateTime.now().toString()));
    }
}
