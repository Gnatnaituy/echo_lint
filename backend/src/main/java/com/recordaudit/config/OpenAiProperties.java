package com.recordaudit.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String audioPath = "/v1/audio/transcriptions";
    private String chatPath = "/v1/chat/completions";
    private String whisperModel = "whisper-1";
    private String screenModel = "gpt-4o-mini";
    private double screenTemperature = 0.1;
    private int fewShotCount = 5;
    private long requestTimeoutSeconds = 300;

    public boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey);
    }
}