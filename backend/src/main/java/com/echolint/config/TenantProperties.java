package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 多租户配置（Batch 5）。
 *
 * <p>本轮只做「租户解析 + 上下文」，<b>不做数据隔离</b>；默认关闭（单租户），
 * 关闭时行为与之前完全一致。HEADER 模式要求请求携带租户头，缺失/非法返回 400，
 * 具体过滤逻辑见 {@code com.echolint.integration.tenant.TenantFilter}。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.tenant")
public class TenantProperties {

    /** 解析模式 */
    public enum Mode {
        /** 单租户：所有请求固定解析为 default */
        SINGLE,
        /** 从请求头读取租户 ID */
        HEADER
    }

    /** 总开关；false 时强制单租户（即使 mode=HEADER 也不校验请求头） */
    private boolean enabled = false;

    /** 解析模式：SINGLE（默认）或 HEADER */
    private Mode mode = Mode.SINGLE;

    /** HEADER 模式下读取租户 ID 的请求头名称 */
    private String header = "X-Tenant-Id";

    /** 生效模式：null 视为 SINGLE，避免配置缺失导致 NPE */
    public Mode effectiveMode() {
        return mode == null ? Mode.SINGLE : mode;
    }

    /** 是否启用「按请求头解析租户」 */
    public boolean headerMode() {
        return enabled && effectiveMode() == Mode.HEADER;
    }

    /** 生效的租户请求头（配置为空时回退默认值） */
    public String effectiveHeader() {
        return StringUtils.hasText(header) ? header.trim() : "X-Tenant-Id";
    }
}
