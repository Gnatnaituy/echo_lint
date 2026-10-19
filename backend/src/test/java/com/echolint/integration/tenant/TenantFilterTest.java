package com.echolint.integration.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户过滤器：设置/清理 TenantContext；HEADER 模式缺失/非法返回 JSON 400；
 * 非 /api/**、OPTIONS 预检与异常路径的行为。
 */
class TenantFilterTest {

    private MockHttpServletRequest apiRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/recordings");
        request.setRequestURI("/api/recordings");
        return request;
    }

    @Test
    void singleTenantResolverSetsDefaultAndClearsAfterRequest() throws Exception {
        TenantFilter filter = new TenantFilter(new SingleTenantResolver(), new ObjectMapper());
        MockHttpServletRequest request = apiRequest("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContext.current().orElse(null));

        filter.doFilter(request, response, chain);

        assertEquals(TenantContext.DEFAULT_TENANT, duringChain.get());
        assertTrue(TenantContext.current().isEmpty(), "请求结束必须清理 ThreadLocal");
    }

    @Test
    void headerModeValidHeaderIsPropagatedToContext() throws Exception {
        TenantFilter filter = new TenantFilter(new HeaderTenantResolver("X-Tenant-Id"), new ObjectMapper());
        MockHttpServletRequest request = apiRequest("GET");
        request.addHeader("X-Tenant-Id", " acme ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContext.current().orElse(null));

        filter.doFilter(request, response, chain);

        assertEquals("acme", duringChain.get());
        assertTrue(TenantContext.current().isEmpty());
        assertEquals(200, response.getStatus());
    }

    @Test
    void headerModeMissingHeaderReturnsJson400() throws Exception {
        TenantFilter filter = new TenantFilter(new HeaderTenantResolver("X-Tenant-Id"), new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest("GET"), response, chain);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":400"), body);
        assertTrue(body.contains("X-Tenant-Id"), body);
        assertNull(chain.getRequest(), "租户校验失败不得进入后续链");
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void headerModeInvalidHeaderReturnsJson400() throws Exception {
        TenantFilter filter = new TenantFilter(new HeaderTenantResolver("X-Tenant-Id"), new ObjectMapper());
        MockHttpServletRequest request = apiRequest("POST");
        request.addHeader("X-Tenant-Id", "not valid tenant!");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("租户标识不合法"));
        assertNull(chain.getRequest());
    }

    @Test
    void resolverReturningBlankIsRejected() throws Exception {
        TenantResolver blankResolver = mock(TenantResolver.class);
        when(blankResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("  ");
        TenantFilter filter = new TenantFilter(blankResolver, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest("GET"), response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void optionsPreflightSkipsTenantValidation() throws Exception {
        TenantResolver resolver = mock(TenantResolver.class);
        TenantFilter filter = new TenantFilter(resolver, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest("OPTIONS"), response, chain);

        assertNotNull(chain.getRequest(), "CORS 预检不带自定义头，必须放行");
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonApiPathsAreNotFiltered() throws Exception {
        TenantResolver resolver = mock(TenantResolver.class);
        TenantFilter filter = new TenantFilter(resolver, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRequestURI("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void contextIsClearedWhenDownstreamThrows() {
        TenantFilter filter = new TenantFilter(new SingleTenantResolver(), new ObjectMapper());
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(apiRequest("GET"), new MockHttpServletResponse(), failingChain));

        assertTrue(TenantContext.current().isEmpty(), "异常路径也必须清理租户上下文");
    }
}
