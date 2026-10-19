package com.echolint.config;

import com.echolint.integration.diarization.DiarizationProvider;
import com.echolint.integration.diarization.UnsupportedDiarizationProvider;
import com.echolint.integration.sso.DisabledSsoProvider;
import com.echolint.integration.sso.SsoProvider;
import com.echolint.integration.streaming.StreamingTranscriptionProvider;
import com.echolint.integration.streaming.UnsupportedStreamingProvider;
import com.echolint.integration.tenant.HeaderTenantResolver;
import com.echolint.integration.tenant.SingleTenantResolver;
import com.echolint.integration.tenant.TenantFilter;
import com.echolint.integration.tenant.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集成适配器装配（Batch 5）。
 *
 * <p>默认全部是 no-op 适配器：租户固定 default、SSO/流式 ASR/说话人分离未启用，
 * 因此不开任何开关时行为与之前完全一致。真正接入外部系统时替换对应 Bean 即可，
 * 但契约与接入步骤见 docs/integrations.md。</p>
 */
@Configuration
public class IntegrationConfig {

    /**
     * 租户解析器：默认单租户；只有 app.tenant.enabled=true 且 mode=HEADER 时才读请求头。
     */
    @Bean
    public TenantResolver tenantResolver(TenantProperties tenantProperties) {
        if (tenantProperties.headerMode()) {
            return new HeaderTenantResolver(tenantProperties.effectiveHeader());
        }
        return new SingleTenantResolver();
    }

    /**
     * 租户过滤器注册：URL 限定 /api/*，并排在 Spring Security 之前（默认 -100），
     * 使 HEADER 模式下缺失/非法租户头稳定返回 JSON 400，而不是先被 401 拦截。
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantResolver tenantResolver,
                                                                       ObjectMapper objectMapper) {
        FilterRegistrationBean<TenantFilter> registration =
                new FilterRegistrationBean<>(new TenantFilter(tenantResolver, objectMapper));
        registration.setName("tenantFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
        return registration;
    }

    /**
     * 默认 SSO 适配器（未启用）。接入真实 OIDC/LDAP 时在实现类上加 {@code @Primary}
     * 或替换本 Bean；状态接口会在适配器返回非空 loginUrl 后展示 SSO 登录入口。
     */
    @Bean
    public SsoProvider ssoProvider() {
        return new DisabledSsoProvider();
    }

    /** 默认流式转写适配器（未实现，start 抛错）；接入时替换本 Bean */
    @Bean
    public StreamingTranscriptionProvider streamingTranscriptionProvider() {
        return new UnsupportedStreamingProvider();
    }

    /** 默认说话人分离适配器（未实现，diarize 抛错）；接入时替换本 Bean */
    @Bean
    public DiarizationProvider diarizationProvider() {
        return new UnsupportedDiarizationProvider();
    }
}
