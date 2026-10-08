package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 语音转写（ASR）配置。
 *
 * 默认指向 OpenAI Whisper；把 base-url 改成本机 whisper.cpp 即可让录音不出本机
 * （见 tools/whisper-server.sh），例如：
 * <pre>
 * WHISPER_BASE_URL=http://host.docker.internal:9900
 * WHISPER_API_KEY=local        # whisper.cpp 不校验，但本服务要求非空
 * </pre>
 *
 * 注意：**前缀必须独立于 `openai`**。曾经把转写密钥写成 `openai.api-key`，
 * 结果 docker-compose 注入的空环境变量 `OPENAI_API_KEY=""` 会按宽松绑定等价于
 * `openai.api-key`，且环境变量优先级高于 application.yml，把 yml 里解析好的值整个盖掉。
 * 独立前缀（whisper.*）与环境变量 WHISPER_* 一一对应，不存在这个遮蔽问题。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "whisper")
public class WhisperProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";

    /** 转写端点路径（OpenAI 兼容：/v1/audio/transcriptions） */
    private String audioPath = "/v1/audio/transcriptions";

    /** 模型名；自建服务通常忽略该值，但请求里仍需带上 */
    private String model = "whisper-1";

    /** 本地 whisper.cpp 处理长音频较慢，默认给宽一些 */
    private long requestTimeoutSeconds = 600;

    public boolean apiKeyConfigured() {
        return StringUtils.hasText(apiKey);
    }
}
