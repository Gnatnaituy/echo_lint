package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSO/LDAP 配置：默认关闭；configured 只看必要密钥是否齐全（与 enabled 解耦）；
 * role-mapping-json 优先，兼容旧键 role-mapping。
 */
class SsoPropertiesTest {

    @Test
    void defaultsAreSafe() {
        SsoProperties properties = new SsoProperties();

        assertFalse(properties.isEnabled());
        assertEquals(SsoProperties.Provider.OIDC, properties.getProvider());
        assertEquals(SsoProperties.Provider.OIDC, properties.effectiveProvider());
        assertFalse(properties.configured());
        assertFalse(properties.roleMappingConfigured());
        assertEquals("", properties.effectiveRoleMapping());
        assertTrue(properties.getClientSecret().isEmpty());
    }

    @Test
    void configuredRequiresIssuerClientIdAndSecret() {
        SsoProperties properties = new SsoProperties();
        properties.setIssuer("https://idp.example.com/realms/echolint");
        properties.setClientId("echolint");
        assertFalse(properties.configured(), "缺 client-secret 不能算已配置");

        properties.setClientSecret("s3cr3t");
        assertTrue(properties.configured(), "configured 与 enabled 无关，便于状态页区分已配置未启用");

        properties.setIssuer("  ");
        assertFalse(properties.configured());
    }

    @Test
    void roleMappingJsonWinsOverLegacyAlias() {
        SsoProperties properties = new SsoProperties();
        properties.setRoleMapping("{\"legacy\":\"VIEWER\"}");
        assertEquals("{\"legacy\":\"VIEWER\"}", properties.effectiveRoleMapping());
        assertTrue(properties.roleMappingConfigured());

        properties.setRoleMappingJson(" {\"echolint-admins\":\"ADMIN\"} ");
        assertEquals("{\"echolint-admins\":\"ADMIN\"}", properties.effectiveRoleMapping());
    }

    @Test
    void ldapProviderIsSupported() {
        SsoProperties properties = new SsoProperties();
        properties.setProvider(SsoProperties.Provider.LDAP);

        assertEquals(SsoProperties.Provider.LDAP, properties.effectiveProvider());
    }

    @Test
    void nullProviderFallsBackToOidc() {
        SsoProperties properties = new SsoProperties();
        properties.setProvider(null);

        assertEquals(SsoProperties.Provider.OIDC, properties.effectiveProvider());
    }
}
