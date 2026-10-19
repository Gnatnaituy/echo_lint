package com.echolint.integration.sso;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 默认 SSO 适配器：永远未启用/未配置，登录地址为空，元数据不含敏感信息。
 */
class DisabledSsoProviderTest {

    private final DisabledSsoProvider provider = new DisabledSsoProvider();

    @Test
    void alwaysDisabledAndNotConfigured() {
        assertEquals("disabled", provider.id());
        assertFalse(provider.enabled());
        assertFalse(provider.configured());
        assertEquals("", provider.loginUrl());
    }

    @Test
    void metadataCarriesOnlyProviderId() {
        assertEquals("disabled", provider.metadata().get("provider"));
        assertEquals(1, provider.metadata().size(), "元数据只允许非敏感字段");
    }
}
