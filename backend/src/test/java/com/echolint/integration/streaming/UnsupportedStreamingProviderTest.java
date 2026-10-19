package com.echolint.integration.streaming;

import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 默认流式转写适配器：未配置；start() 显式抛 400 业务异常（避免静默无转写）。
 */
class UnsupportedStreamingProviderTest {

    private final UnsupportedStreamingProvider provider = new UnsupportedStreamingProvider();

    @Test
    void reportsUnsupported() {
        assertEquals("unsupported", provider.id());
        assertFalse(provider.configured());
    }

    @Test
    void startFailsExplicitly() {
        BizException e = assertThrows(BizException.class,
                () -> provider.start(new StreamingTranscriptionProvider.StreamOptions("zh", 16000)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("流式转写未启用"));
    }
}
