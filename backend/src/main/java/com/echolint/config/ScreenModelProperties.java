package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 语义复筛 / 敏感词挖掘所用的模型供应商清单。
 *
 * 复筛与词挖掘都是 OpenAI 兼容的 chat completions 调用，因此「切换供应商」等价于
 * 换 base-url + api-key + model 三件套；语音转写（Whisper）不在其中，仍固定走
 * {@link WhisperProperties}（DeepSeek 等 chat 供应商不提供转写能力）。
 *
 * 当前生效的供应商/模型在运行时可切换，并持久化到 app_settings 表，见 ScreenModelService。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.screen-models")
public class ScreenModelProperties {

    /** 未做过运行时切换时的默认供应商 id */
    private String defaultProvider = "openai";

    /** 复筛温度（词挖掘用固定的 0.2，见 WordMiningService） */
    private double temperature = 0.1;

    /** few-shot 注入的人工复核案例条数 */
    private int fewShotCount = 5;

    private long requestTimeoutSeconds = 300;

    /**
     * 单次送入模型的转写文本上限（字符）。超过后按 {@code chunkChars} 切块、带重叠逐块复核，
     * 避免长录音被静默截断导致后半段漏检（Batch 2b-2）。
     */
    private int chunkChars = 12000;

    /** 最多复核的分块数；超出的分块直接截断（日志注明），防止超长录音把成本放大到不可控 */
    private int maxChunks = 6;

    /**
     * 判定违规所需的最低置信度：{@code violation=true} 但置信度低于该值时降级人工复核，
     * 不允许自动放行（Batch 2b-2）。
     */
    private double minConfidence = 0.6;

    /**
     * 每日 token 配额（输入 + 输出，所有供应商合计），0 = 不限。
     * 超限后不再发起 AI 调用，抛错由流水线降级人工复核（Batch 3a）。
     */
    private long dailyTokenLimit = 0;

    /** 单个供应商每分钟调用次数上限，0 = 不限（进程内计数，Batch 3a） */
    private int requestsPerMinute = 0;

    /**
     * 语义复筛 A/B 分流（Batch 3b-2，只读配置）：开启且备用供应商可用时，
     * 按 percent 概率走 B 组；否则走当前生效模型（A 组）。词挖掘不参与分流。
     */
    private Ab ab = new Ab();

    private List<Provider> providers = new ArrayList<>();

    /** 生效的单块字符数：下限 500，避免配置过小把提示词切碎 */
    public int effectiveChunkChars() {
        return Math.max(500, chunkChars);
    }

    /** 生效的最大分块数：1~50，避免 0/负数导致不复核或异常配置放大成本 */
    public int effectiveMaxChunks() {
        return Math.min(50, Math.max(1, maxChunks));
    }

    /** 生效的最低置信度：钳制到 0~1，NaN 回退默认 0.6 */
    public double effectiveMinConfidence() {
        if (Double.isNaN(minConfidence)) {
            return 0.6;
        }
        return Math.min(1.0, Math.max(0.0, minConfidence));
    }

    public Optional<Provider> find(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        String key = id.trim();
        return providers.stream().filter(p -> key.equalsIgnoreCase(p.getId())).findFirst();
    }

    /** 默认供应商；配置里的 default-provider 写错时回退到第一个，避免整条流水线不可用 */
    public Optional<Provider> defaultProvider() {
        return find(defaultProvider).or(() -> providers.stream().findFirst());
    }

    /**
     * 语义复筛 A/B 分流配置。
     */
    @Getter
    @Setter
    public static class Ab {

        /** 是否开启分流；默认关闭 */
        private boolean enabled = false;

        /** B 组流量百分比（0~100），0 等价于不开启，100 表示全部走 B */
        private int percent = 0;

        /** B 组供应商 id（必须是已存在且启用的供应商） */
        private String providerId;

        /** B 组模型名；留空回退到该供应商的 default-model */
        private String model;

        /** 生效的 B 组百分比：钳制到 0~100，异常配置按 0 处理 */
        public int effectivePercent() {
            return Math.min(100, Math.max(0, percent));
        }
    }

    /**
     * 单个模型供应商（OpenAI 兼容端点）
     */
    @Getter
    @Setter
    public static class Provider {

        /** 供应商标识，持久化用（openai / deepseek / 自建网关…） */
        private String id;

        /** 界面展示名，缺省回退到 id */
        private String label;

        private String baseUrl = "https://api.openai.com";

        private String apiKey = "";

        /** 密钥对应的环境变量名，仅用于提示文案（如 DEEPSEEK_API_KEY） */
        private String apiKeyEnv = "";

        /** chat completions 路径：OpenAI 为 /v1/chat/completions，DeepSeek 为 /chat/completions */
        private String chatPath = "/v1/chat/completions";

        /** 该供应商的默认模型 */
        private String defaultModel;

        /** 界面候选模型（允许手填未列出的新模型，此处仅作下拉候选项） */
        private List<String> models = new ArrayList<>();

        /** 是否支持 response_format={"type":"json_object"} */
        private boolean jsonMode = true;

        /** 输出上限，防止 JSON 被中途截断；为空则不发送该字段 */
        private Integer maxTokens;

        /** 输入单价（USD / 1K tokens），随供应商落库后用于成本快照 */
        private BigDecimal inputPricePer1k = BigDecimal.ZERO;

        /** 输出单价（USD / 1K tokens） */
        private BigDecimal outputPricePer1k = BigDecimal.ZERO;

        /** 透传的额外请求字段（如 DeepSeek 的 thinking / reasoning_effort） */
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        public boolean apiKeyConfigured() {
            return StringUtils.hasText(apiKey);
        }

        public String displayLabel() {
            return StringUtils.hasText(label) ? label : id;
        }

        /** 未指定模型时回退到 default-model，再回退到候选列表首项 */
        public String resolvedModel(String requested) {
            if (StringUtils.hasText(requested)) {
                return requested.trim();
            }
            if (StringUtils.hasText(defaultModel)) {
                return defaultModel.trim();
            }
            return models.isEmpty() ? "" : models.get(0);
        }

        /** 未配置供应商时的提示文案 */
        public String apiKeyHint() {
            return StringUtils.hasText(apiKeyEnv)
                    ? "请设置环境变量 " + apiKeyEnv
                    : "请在配置中为供应商 " + id + " 填写 api-key";
        }
    }
}
