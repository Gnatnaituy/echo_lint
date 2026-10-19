package com.echolint.security;

import com.echolint.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 API 过滤器（Batch 4a）：仅 /api/open/** 生效；缺失/无效/停用返回 JSON 401；
 * 有效密钥写入上下文并放行；非开放路径与 OPTIONS 预检不拦截。
 */
class ApiKeyAuthFilterTest {

    private ApiKeyService apiKeyService;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        filter = new ApiKeyAuthFilter(apiKeyService, new ObjectMapper());
    }

    private MockHttpServletRequest openRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/open/recordings/upload");
        request.setRequestURI("/api/open/recordings/upload");
        return request;
    }

    @Test
    void missingHeaderReturnsJson401() throws Exception {
        MockHttpServletRequest request = openRequest("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("X-API-Key"), body);
        assertNull(chain.getRequest(), "未通过鉴权不得继续调用后续链");
        verify(apiKeyService, never()).authenticate(anyString());
    }

    @Test
    void invalidOrDisabledKeyReturnsJson401() throws Exception {
        when(apiKeyService.authenticate("elk_invalid")).thenReturn(Optional.empty());
        MockHttpServletRequest request = openRequest("POST");
        request.addHeader(ApiKeyAuthFilter.HEADER, "elk_invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("无效或已停用"));
        assertNull(chain.getRequest());
    }

    @Test
    void validKeySetsContextAndContinues() throws Exception {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(3L, "ci-runner", "elk_abcd");
        when(apiKeyService.authenticate("elk_valid")).thenReturn(Optional.of(context));
        MockHttpServletRequest request = openRequest("POST");
        request.addHeader(ApiKeyAuthFilter.HEADER, "elk_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(context, request.getAttribute(ApiKeyAuthFilter.ATTR_CONTEXT));
        assertNotNull(chain.getRequest(), "有效密钥应继续后续链");
    }

    @Test
    void nonOpenApiPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/recordings/1");
        request.setRequestURI("/api/recordings/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "非开放 API 路径应直接放行");
        verify(apiKeyService, never()).authenticate(anyString());
    }

    @Test
    void optionsPreflightIsNotBlocked() throws Exception {
        MockHttpServletRequest request = openRequest("OPTIONS");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        verify(apiKeyService, never()).authenticate(anyString());
    }

    @Test
    void similarPathIsNotTreatedAsOpenApi() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/openapi");
        request.setRequestURI("/api/openapi");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "前缀必须精确到 /api/open/");
        verify(apiKeyService, never()).authenticate(anyString());
    }
}
