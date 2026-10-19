package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多租户配置：默认关闭（单租户），只有 enabled + HEADER 同时满足才按请求头解析。
 */
class TenantPropertiesTest {

    @Test
    void defaultsToSingleTenantDisabled() {
        TenantProperties properties = new TenantProperties();

        assertFalse(properties.isEnabled());
        assertEquals(TenantProperties.Mode.SINGLE, properties.getMode());
        assertEquals("X-Tenant-Id", properties.getHeader());
        assertFalse(properties.headerMode());
        assertEquals(TenantProperties.Mode.SINGLE, properties.effectiveMode());
        assertEquals("X-Tenant-Id", properties.effectiveHeader());
    }

    @Test
    void headerModeRequiresEnabledAndHeaderMode() {
        TenantProperties properties = new TenantProperties();

        properties.setMode(TenantProperties.Mode.HEADER);
        assertFalse(properties.headerMode(), "enabled=false 时即使 mode=HEADER 也必须保持单租户");

        properties.setEnabled(true);
        assertTrue(properties.headerMode());
    }

    @Test
    void enabledSingleModeDoesNotReadHeader() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);

        assertFalse(properties.headerMode());
        assertEquals(TenantProperties.Mode.SINGLE, properties.effectiveMode());
    }

    @Test
    void blankHeaderNameFallsBackToDefault() {
        TenantProperties properties = new TenantProperties();
        properties.setHeader("   ");

        assertEquals("X-Tenant-Id", properties.effectiveHeader());
    }

    @Test
    void customHeaderNameIsTrimmed() {
        TenantProperties properties = new TenantProperties();
        properties.setHeader(" X-Org-Id ");

        assertEquals("X-Org-Id", properties.effectiveHeader());
    }

    @Test
    void nullModeFallsBackToSingle() {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        properties.setMode(null);

        assertEquals(TenantProperties.Mode.SINGLE, properties.effectiveMode());
        assertFalse(properties.headerMode());
    }
}
