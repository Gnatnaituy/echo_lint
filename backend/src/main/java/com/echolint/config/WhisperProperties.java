package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 语音转写（ASR）配置：只支持自建/本机 whisper.cpp（见 tools/whisper-server.sh）。
 *
 * <pre>
 * WHISPER_BASE_URL=http://host.docker.internal:9900
 * WHISPER_API_KEY=local        # whisper.cpp 不校验，但本服务要求非空
 * </pre>
 *
 * 独立 whisper.* 前缀与 WHISPER_* 环境变量一一对应，避免与其它供应商变量互相遮蔽。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "whisper")
public class WhisperProperties {

    private String apiKey = "local";
    private String baseUrl = "http://127.0.0.1:9900";

    /** 转写端点路径（兼容 whisper.cpp 伪装的 /v1/audio/transcriptions） */
    private String audioPath = "/v1/audio/transcriptions";

    /** 模型名；自建服务通常忽略该值，但请求里仍需带上 */
    private String model = "whisper-1";

    /** 本地 whisper.cpp 处理长音频较慢，默认给宽一些 */
    private long requestTimeoutSeconds = 600;

    public boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey);
    }
}
