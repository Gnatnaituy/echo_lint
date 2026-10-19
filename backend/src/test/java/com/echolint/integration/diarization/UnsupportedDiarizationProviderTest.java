package com.echolint.integration.diarization;

import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 默认说话人分离适配器：未配置；diarize() 显式抛错，由调用方降级为单说话人。
 */
class UnsupportedDiarizationProviderTest {

    private final UnsupportedDiarizationProvider provider = new UnsupportedDiarizationProvider();

    @Test
    void reportsUnsupported() {
        assertEquals("unsupported", provider.id());
        assertFalse(provider.configured());
    }

    @Test
    void diarizeFailsExplicitly() {
        BizException e = assertThrows(BizException.class,
                () -> provider.diarize(Path.of("/tmp/call.mp3")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("说话人分离未启用"));
    }
}
