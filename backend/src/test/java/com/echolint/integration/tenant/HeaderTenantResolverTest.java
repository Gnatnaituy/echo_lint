package com.echolint.integration.tenant;

import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HEADER 模式租户解析：合法 ID 原样返回（去首尾空白）；缺失/非法统一 400，且不回显原始值。
 */
class HeaderTenantResolverTest {

    private final HeaderTenantResolver resolver = new HeaderTenantResolver("X-Tenant-Id");

    private MockHttpServletRequest requestWithHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/recordings");
        if (value != null) {
            request.addHeader("X-Tenant-Id", value);
        }
        return request;
    }

    @Test
    void validTenantIdIsReturnedTrimmed() {
        assertEquals("acme", resolver.resolve(requestWithHeader("  acme ")));
        assertEquals("tenant_01-A", resolver.resolve(requestWithHeader("tenant_01-A")));
    }

    @Test
    void customHeaderNameIsUsed() {
        HeaderTenantResolver custom = new HeaderTenantResolver("X-Org-Id");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/recordings");
        request.addHeader("X-Org-Id", "org-1");

        assertEquals("org-1", custom.resolve(request));
        assertEquals("X-Org-Id", custom.headerName());
    }

    @Test
    void missingHeaderIsRejectedWith400() {
        BizException e = assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader(null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("X-Tenant-Id"));
    }

    @Test
    void blankHeaderIsRejected() {
        BizException e = assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader("   ")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void invalidCharactersAreRejectedWithoutEchoingValue() {
        String malicious = "<script>alert(1)</script>";
        BizException e = assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader(malicious)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("不合法"));
        assertFalse(e.getMessage().contains("script"), "错误信息不得回显原始请求头值");
    }

    @Test
    void leadingSeparatorAndOverlongIdsAreRejected() {
        assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader("-acme")));
        assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader("_acme")));
        assertThrows(BizException.class, () -> resolver.resolve(requestWithHeader("a".repeat(65))));
    }

    @Test
    void maxLengthIdIsAccepted() {
        String id = "a".repeat(64);

        assertEquals(id, resolver.resolve(requestWithHeader(id)));
    }

    @Test
    void blankConfiguredHeaderFallsBackToDefaultName() {
        HeaderTenantResolver fallback = new HeaderTenantResolver("  ");

        assertEquals("X-Tenant-Id", fallback.headerName());
    }
}
