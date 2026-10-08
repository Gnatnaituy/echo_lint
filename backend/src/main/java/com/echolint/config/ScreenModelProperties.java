package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

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

    private List<Provider> providers = new ArrayList<>();

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
