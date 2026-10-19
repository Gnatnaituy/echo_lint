package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 说话人分离（diarization）适配器配置（Batch 5）。
 *
 * <p>仅单声道录音需要（双声道仍按现有左右声道拆分方案）；默认关闭，
 * 不改变现有流水线行为。真正接入需实现 {@code DiarizationProvider}（见 docs/integrations.md）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.diarization")
public class DiarizationProperties {

    /** 总开关；关闭时 diarize() 抛「未配置」，流水线继续按单说话人处理 */
    private boolean enabled = false;

    /** 供应商名称（如 pyannote / aliyun / local），仅用于展示与选型 */
    private String provider = "";

    /** 分离服务地址（http(s)://） */
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
