package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** provider 配置就绪判定与未就绪说明（切换拦截 / 转写失败原因共用）。 */
class AsrProviderTest {

    private AsrProvider.Config config(String baseUrl, String apiKey, String model) {
        return new AsrProvider.Config(
                baseUrl, apiKey, model, "/v1/audio/transcriptions", List.of("zh"), 600);
    }

    @Test
    void configReadyRequiresBaseUrlApiKeyAndModel() {
        assertTrue(config("http://127.0.0.1:9900", "local", "whisper-1").ready());
        assertFalse(config("", "local", "whisper-1").ready());
        assertFalse(config("http://127.0.0.1:9900", "  ", "whisper-1").ready());
        assertFalse(config("http://127.0.0.1:9900", "local", null).ready());
    }

    @Test
    void missingDescriptionPointsToSettingsPageInsteadOfEnv() {
        String dashscope =
                AsrProvider.missingDescription(
                        config("https://dashscope.aliyuncs.com", "", "model"));
        assertTrue(dashscope.contains("未配置API Key"), dashscope);
        assertTrue(dashscope.contains("请在设置页录入 API Key"), dashscope);
        assertFalse(dashscope.contains("DASHSCOPE_API_KEY"), dashscope);
        assertFalse(dashscope.contains("环境变量"), dashscope);

        String local = AsrProvider.missingDescription(config("", "", ""));
        assertTrue(local.contains("服务地址"), local);
        assertTrue(local.contains("API Key"), local);
        assertTrue(local.contains("模型"), local);
        assertTrue(local.contains("请在设置页录入 API Key"), local);
        assertFalse(local.contains("WHISPER_API_KEY"), local);
        assertFalse(local.contains("环境变量"), local);
    }

    @Test
    void missingDescriptionIsEmptyWhenReady() {
        assertEquals(
                "",
                AsrProvider.missingDescription(
                        config("http://127.0.0.1:9900", "local", "whisper-1")));
    }

    @Test
    void errorMessageFallsBackToExceptionType() {
        assertEquals("RuntimeException", AsrProvider.errorMessage(new RuntimeException()));
        assertEquals("boom", AsrProvider.errorMessage(new RuntimeException("boom")));
        assertEquals("未知错误", AsrProvider.errorMessage(null));
    }
}
