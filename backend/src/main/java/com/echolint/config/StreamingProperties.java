package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 流式（实时）语音转写适配器配置（Batch 5）。
 *
 * <p>默认关闭，且<b>不改变现有批量转写流程</b>；本轮只提供适配器契约与状态，
 * 真正接入需实现 {@code StreamingTranscriptionProvider}（见 docs/integrations.md）。
 * api-key 只从环境变量/配置读取，状态接口只返回「是否已配置」。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.asr.streaming")
public class StreamingProperties {

    /** 总开关；关闭时 start() 直接抛「未配置」，前端不应展示实时转写入口 */
    private boolean enabled = false;

    /** 供应商名称（如 funasr / deepgram / aliyun），仅用于展示与选型 */
    private String provider = "";

    /** 流式网关地址（ws(s):// 或 https://，具体协议由适配器约定） */
    private String endpoint = "";

    /** 供应商密钥（绝不外泄） */
    private String apiKey = "";

    /** 配置是否齐全（不判断 enabled，便于状态区分「已配置未启用」） */
    public boolean configured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(apiKey);
    }

    /** 展示用供应商名（未配置时回退 unsupported） */
    public String providerOrDefault() {
        return StringUtils.hasText(provider) ? provider.trim() : "unsupported";
    }
}
