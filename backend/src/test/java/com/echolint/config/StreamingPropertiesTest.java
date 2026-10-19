package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式 ASR 配置：默认关闭；configured 只反映 endpoint + api-key 是否齐全（与 enabled 解耦）。
 */
class StreamingPropertiesTest {

    @Test
    void defaultsAreDisabled() {
        StreamingProperties properties = new StreamingProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.configured());
        assertEquals("", properties.getProvider());
        assertEquals("unsupported", properties.providerOrDefault());
    }

    @Test
    void configuredNeedsEndpointAndApiKey() {
        StreamingProperties properties = new StreamingProperties();
        properties.setEndpoint("wss://asr.example.com/stream");
        assertFalse(properties.configured());

        properties.setApiKey("asr-secret");
        assertTrue(properties.configured());

        properties.setEndpoint("  ");
        assertFalse(properties.configured());
    }

    @Test
    void providerNameIsTrimmedAndFallsBack() {
        StreamingProperties properties = new StreamingProperties();
        properties.setProvider("  funasr ");
        assertEquals("funasr", properties.providerOrDefault());

        properties.setProvider("   ");
        assertEquals("unsupported", properties.providerOrDefault());
    }
}
