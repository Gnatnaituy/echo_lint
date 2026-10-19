package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 说话人分离配置：默认关闭；configured 只反映 endpoint + api-key 是否齐全。
 */
class DiarizationPropertiesTest {

    @Test
    void defaultsAreDisabled() {
        DiarizationProperties properties = new DiarizationProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.configured());
        assertEquals("unsupported", properties.providerOrDefault());
    }

    @Test
    void configuredNeedsEndpointAndApiKey() {
        DiarizationProperties properties = new DiarizationProperties();
        properties.setEndpoint("https://diar.example.com/v1");
        assertFalse(properties.configured());

        properties.setApiKey("dia-secret");
        assertTrue(properties.configured());
    }

    @Test
    void providerNameIsTrimmed() {
        DiarizationProperties properties = new DiarizationProperties();
        properties.setProvider(" pyannote ");

        assertEquals("pyannote", properties.providerOrDefault());
    }
}
