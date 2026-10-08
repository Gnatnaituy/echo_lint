package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * OpenAI 配置 —— 现在只服务于**语音转写（Whisper）**。
 *
 * AI 语义复筛与敏感词挖掘的模型已抽到 {@link ScreenModelProperties}，支持运行时切换
 * （含 DeepSeek 等 OpenAI 兼容供应商）；DeepSeek 不提供转写能力，故转写仍固定走这里。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String audioPath = "/v1/audio/transcriptions";
    private String whisperModel = "whisper-1";
    private long requestTimeoutSeconds = 300;

    public boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey);
    }
}
