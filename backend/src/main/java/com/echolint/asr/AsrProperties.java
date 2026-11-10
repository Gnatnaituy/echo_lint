package com.echolint.asr;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音转写（ASR）env 默认值：{@code asr.*}（见 docs/asr-and-settings-refactor.md §2.1）。
 *
 * <p>这里只承载「尚未在系统设置页保存过」时的默认值；管理员在设置页保存后以 {@code app_settings} 为准（{@code AsrSettingsService} 负责 DB
 * 优先、env 兜底）。 环境变量沿用既有的 {@code WHISPER_*}，百炼新增 {@code DASHSCOPE_ASR_*}。
 *
 * <p><b>注意</b>：{@link Local#getApiKey()} / {@link DashScope#getApiKey()} 不是运行时密钥来源，仅由 {@link
 * AsrKeyBootstrapRunner} 在首次启动时读取并加密写入 {@code app_settings}；之后运行时只读数据库 （界面只回掩码），见
 * docs/asr-and-settings-refactor.md §6.2。
 *
 * <pre>
 * ASR_TIMEOUT_SECONDS=600                       # 单次转写总预算（30~1800）
 * WHISPER_BASE_URL=http://127.0.0.1:9900        # 本地 whisper.cpp
 * WHISPER_API_KEY=local                         # 本地不校验，但本服务要求非空
 * WHISPER_MODEL=whisper-1
 * DASHSCOPE_ASR_BASE_URL=https://dashscope.aliyuncs.com   # DashScope API 根，不是 compatible-mode 地址
 * DASHSCOPE_API_KEY=sk-...                      # 与 RAG embedding 共用同一个百炼 Key
 * DASHSCOPE_ASR_MODEL=qwen-audio-3.1-asr-flash-filetrans
 * DASHSCOPE_ASR_LANGUAGE_HINTS=zh               # 逗号分隔，第一项作为结果 language
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "asr")
public class AsrProperties {

    public static final long MIN_TIMEOUT_SECONDS = 30;
    public static final long MAX_TIMEOUT_SECONDS = 1800;
    public static final long DEFAULT_TIMEOUT_SECONDS = 600;

    /** 单次转写总预算（本地 block 超时 / 云端「提交 + 轮询 + 下载」总预算） */
    private long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    private Local local = new Local();

    private DashScope dashscope = new DashScope();

    /** 钳制到 30~1800 秒（非法/越界值不让转写无限等待或超时过短） */
    public long effectiveTimeoutSeconds() {
        return clampTimeoutSeconds(timeoutSeconds);
    }

    public static long clampTimeoutSeconds(long seconds) {
        return Math.min(MAX_TIMEOUT_SECONDS, Math.max(MIN_TIMEOUT_SECONDS, seconds));
    }

    /** 本机 whisper.cpp（OpenAI 兼容接口） */
    @Getter
    @Setter
    public static class Local {
        private String baseUrl = "http://127.0.0.1:9900";

        /** 仅首次启动 bootstrap 的引导值（whisper.cpp 不校验密钥，未配置时用 local） */
        private String apiKey = "local";

        private String model = "whisper-1";

        /** 转写端点路径（whisper.cpp 伪装的 OpenAI 兼容路径） */
        private String audioPath = "/v1/audio/transcriptions";
    }

    /** 阿里云百炼录音文件识别（DashScope 原生 API，不是 compatible-mode） */
    @Getter
    @Setter
    public static class DashScope {
        private String baseUrl = "https://dashscope.aliyuncs.com";

        /** 仅首次启动 bootstrap 的引导值；留空表示未配置（可在设置页录入，加密落库） */
        private String apiKey = "";

        private String model = "qwen-audio-3.1-asr-flash-filetrans";

        /** 逗号分隔语言提示；第一项作为转写结果的 language */
        private String languageHints = "zh";

        /** 设置页下拉候选（允许手填其它模型名） */
        private List<String> models =
                List.of("qwen-audio-3.1-asr-flash-filetrans", "qwen-audio-3.0-asr-flash-filetrans");
    }
}
