package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Webhook 通知配置：默认关闭、仅接受 http(s)、超时与阈值钳制。
 */
class NotifyPropertiesTest {

    @Test
    void defaultsAreSafe() {
        NotifyProperties properties = new NotifyProperties();

        assertFalse(properties.isEnabled(), "默认不开启，避免未配置接收端时静默丢通知");
        assertFalse(properties.urlConfigured());
        assertFalse(properties.httpUrlConfigured());
        assertFalse(properties.usable());
        assertEquals(5, properties.getTimeoutSeconds());
        assertEquals(50, properties.getBacklogThreshold());
        assertEquals(5, properties.effectiveTimeoutSeconds());
        assertEquals(50, properties.effectiveBacklogThreshold());
    }

    @Test
    void onlyHttpSchemesAreUsable() {
        NotifyProperties properties = new NotifyProperties();
        properties.setEnabled(true);

        properties.setWebhookUrl("ftp://example.com/hook");
        assertTrue(properties.urlConfigured());
        assertFalse(properties.httpUrlConfigured(), "非 http(s) 协议不能用于 Webhook");
        assertFalse(properties.usable());

        properties.setWebhookUrl("not a url");
        assertFalse(properties.httpUrlConfigured());

        properties.setWebhookUrl("https://example.com/hook?key=secret");
        assertTrue(properties.httpUrlConfigured());
        assertTrue(properties.usable());
    }

    @Test
    void timeoutAndThresholdAreClamped() {
        NotifyProperties properties = new NotifyProperties();

        properties.setTimeoutSeconds(0);
        assertEquals(1, properties.effectiveTimeoutSeconds());
        properties.setTimeoutSeconds(9999);
        assertEquals(60, properties.effectiveTimeoutSeconds());

        properties.setBacklogThreshold(0);
        assertEquals(1, properties.effectiveBacklogThreshold());
        properties.setBacklogThreshold(-5);
        assertEquals(1, properties.effectiveBacklogThreshold());
    }
}
