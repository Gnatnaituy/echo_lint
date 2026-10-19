package com.echolint.service;

import com.echolint.config.DiarizationProperties;
import com.echolint.config.SsoProperties;
import com.echolint.config.StreamingProperties;
import com.echolint.config.TenantProperties;
import com.echolint.integration.sso.SsoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 集成适配器状态汇总（Batch 5）。
 *
 * <p>供 ADMIN 的系统设置页（{@code GET /api/settings/integrations}）与登录页
 * （{@code GET /api/auth/sso/config}）使用。<b>只返回是否启用/配置、供应商名与登录地址，
 * 绝不包含 client-secret、api-key、绑定密码等敏感值。</b></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationStatusService {

    private final TenantProperties tenantProperties;
    private final SsoProperties ssoProperties;
    private final StreamingProperties streamingProperties;
    private final DiarizationProperties diarizationProperties;
    private final SsoProvider ssoProvider;

    /** 多租户状态：mode=SINGLE|HEADER，provider=single|header（解析器标识） */
    public record TenantStatus(boolean enabled, String mode, String provider, boolean configured) {
    }

    /** SSO 状态（公开配置接口复用同一结构）：无任何密钥字段 */
    public record SsoStatus(boolean enabled, String provider, boolean configured, String loginUrl) {
    }

    /** 流式转写状态 */
    public record StreamingStatus(boolean enabled, String provider, boolean configured) {
    }

    /** 说话人分离状态 */
    public record DiarizationStatus(boolean enabled, String provider, boolean configured) {
    }

    /** 四类集成状态总览 */
    public record IntegrationsView(TenantStatus tenant,
                                   SsoStatus sso,
                                   StreamingStatus streaming,
                                   DiarizationStatus diarization) {
    }

    public IntegrationsView status() {
        return new IntegrationsView(tenantStatus(), ssoStatus(), streamingStatus(), diarizationStatus());
    }

    public TenantStatus tenantStatus() {
        return new TenantStatus(
                tenantProperties.isEnabled(),
                tenantProperties.effectiveMode().name(),
                tenantProperties.headerMode() ? "header" : "single",
                true);
    }

    public SsoStatus ssoStatus() {
        return new SsoStatus(
                ssoProperties.isEnabled(),
                ssoProperties.effectiveProvider().name(),
                ssoProperties.configured(),
                safeLoginUrl());
    }

    public StreamingStatus streamingStatus() {
        return new StreamingStatus(
                streamingProperties.isEnabled(),
                streamingProperties.providerOrDefault(),
                streamingProperties.configured());
    }

    public DiarizationStatus diarizationStatus() {
        return new DiarizationStatus(
                diarizationProperties.isEnabled(),
                diarizationProperties.providerOrDefault(),
                diarizationProperties.configured());
    }

    /** 适配器可能尚未实现/抛错：状态接口必须保持可用，loginUrl 失败按空处理 */
    private String safeLoginUrl() {
        try {
            String url = ssoProvider.loginUrl();
            return url == null ? "" : url.trim();
        } catch (Exception e) {
            log.warn("读取 SSO 登录地址失败（按未配置处理）：{}", e.getMessage());
            return "";
        }
    }
}
