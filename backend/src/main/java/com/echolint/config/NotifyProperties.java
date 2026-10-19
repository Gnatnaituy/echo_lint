package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * Webhook 通知告警配置（企微/钉钉/自定义接收端均可，接收端只需能收 JSON POST）。
 *
 * <p>安全约束：webhook-url 只来自配置（部署时通过环境变量注入），不提供运行时改写接口，
 * 避免普通用户把服务器当作 SSRF 跳板。签名密钥同理只来自配置，且永远不写日志。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.notify")
public class NotifyProperties {

    /** 总开关；关闭时不发送任何通知（只记 debug 日志），也不影响主流程 */
    private boolean enabled = false;

    /** 接收端地址（http/https）；为空视为未配置 */
    private String webhookUrl = "";

    /** 可选 HMAC-SHA256 签名密钥；为空则不发送签名头 */
    private String webhookSecret = "";

    /** 单次请求超时（秒） */
    private int timeoutSeconds = 5;

    /** 待复核积压告警阈值（条） */
    private int backlogThreshold = 50;

    /** 是否配置了地址（仅判断非空） */
    public boolean urlConfigured() {
        return StringUtils.hasText(webhookUrl);
    }

    /**
     * 是否配置了可用的 http(s) 地址；非 http(s) 协议直接视为不可用（防 SSRF 到 file:/jar:）。
     */
    public boolean httpUrlConfigured() {
        if (!urlConfigured()) {
            return false;
        }
        try {
            URI uri = URI.create(webhookUrl.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 是否真的会发送通知（开启且地址可用） */
    public boolean usable() {
        return enabled && httpUrlConfigured();
    }

    /** 生效超时：钳制到 1~60 秒，避免 0/负数导致立即失败，也避免误配置成几十分钟卡住线程 */
    public int effectiveTimeoutSeconds() {
        return Math.min(60, Math.max(1, timeoutSeconds));
    }

    /** 生效阈值：至少 1，避免 0 时每天无意义地告警 */
    public int effectiveBacklogThreshold() {
        return Math.max(1, backlogThreshold);
    }
}
