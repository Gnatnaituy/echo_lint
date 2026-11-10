package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 旧版本地 whisper.cpp 转写配置（兼容保留）。
 *
 * <p>语音转写已改为双 provider（本地 whisper.cpp / 阿里云百炼）运行时切换，配置迁到 {@code asr.*}（见 {@code
 * com.echolint.asr.AsrProperties}，docs/asr-and-settings-refactor.md §2）； 其中本地 provider 的 base-url /
 * api-key / model 仍复用 {@code WHISPER_*} 环境变量。 本类不再被转写链路使用：{@link #requestTimeoutSeconds} 已由 {@code
 * asr.timeout-seconds} 接管， 保留仅为兼容旧的 yml 配置，避免升级时报未知属性。
 *
 * <pre>
 * WHISPER_BASE_URL=http://host.docker.internal:9900
 * WHISPER_API_KEY=local        # whisper.cpp 不校验，但本服务要求非空
 * </pre>
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
