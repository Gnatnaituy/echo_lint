package com.echolint.config;

import com.echolint.integration.diarization.DiarizationProvider;
import com.echolint.integration.diarization.UnsupportedDiarizationProvider;
import com.echolint.integration.sso.DisabledSsoProvider;
import com.echolint.integration.sso.SsoProvider;
import com.echolint.integration.streaming.StreamingTranscriptionProvider;
import com.echolint.integration.streaming.UnsupportedStreamingProvider;
import com.echolint.integration.tenant.HeaderTenantResolver;
import com.echolint.integration.tenant.SingleTenantResolver;
import com.echolint.integration.tenant.TenantFilter;
import com.echolint.integration.tenant.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成装配（Batch 5）：默认单租户 + no-op 适配器；HEADER 模式选择头部解析器；
 * 租户过滤器注册在 Spring Security 之前且只作用于 /api/*。
 */
class IntegrationConfigTest {

    private final IntegrationConfig config = new IntegrationConfig();

    @Test
    void defaultSelectionIsSingleTenant() {
        TenantResolver resolver = config.tenantResolver(new TenantProperties());

        assertInstanceOf(SingleTenantResolver.class, resolver);
        assertEquals("default", resolver.resolve(new MockHttpServletRequest("GET", "/api/recordings")));
    }

    @Test
    void headerModeSelectsHeaderResolver() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        properties.setMode(TenantProperties.Mode.HEADER);
        properties.setHeader("X-Org-Id");

        TenantResolver resolver = config.tenantResolver(properties);

        assertInstanceOf(HeaderTenantResolver.class, resolver);
        assertEquals("X-Org-Id", ((HeaderTenantResolver) resolver).headerName());
    }

    @Test
    void enabledSingleModeStillUsesSingleResolver() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        properties.setMode(TenantProperties.Mode.SINGLE);

        assertInstanceOf(SingleTenantResolver.class, config.tenantResolver(properties));
    }

    @Test
    void tenantFilterIsRegisteredForApiBeforeSecurity() {
        FilterRegistrationBean<TenantFilter> registration =
                config.tenantFilterRegistration(new SingleTenantResolver(), new ObjectMapper());

        assertEquals(SecurityProperties.DEFAULT_FILTER_ORDER - 10, registration.getOrder(),
                "必须在 Spring Security 之前执行，保证租户缺失稳定返回 400");
        assertTrue(registration.getUrlPatterns().contains("/api/*"));
        assertNotNull(registration.getFilter());
    }

    @Test
    void defaultAdaptersAreNoOp() {
        SsoProvider sso = config.ssoProvider();
        assertInstanceOf(DisabledSsoProvider.class, sso);
        assertFalse(sso.enabled());
        assertEquals("", sso.loginUrl());

        StreamingTranscriptionProvider streaming = config.streamingTranscriptionProvider();
        assertInstanceOf(UnsupportedStreamingProvider.class, streaming);
        assertFalse(streaming.configured());

        DiarizationProvider diarization = config.diarizationProvider();
        assertInstanceOf(UnsupportedDiarizationProvider.class, diarization);
        assertFalse(diarization.configured());
    }
}
