package com.echolint.service;

import com.echolint.config.DiarizationProperties;
import com.echolint.config.SsoProperties;
import com.echolint.config.StreamingProperties;
import com.echolint.config.TenantProperties;
import com.echolint.integration.sso.DisabledSsoProvider;
import com.echolint.integration.sso.SsoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成状态汇总：默认全关、配置项映射、SSO 登录地址来自适配器、状态序列化绝不含密钥。
 */
class IntegrationStatusServiceTest {

    private TenantProperties tenantProperties;
    private SsoProperties ssoProperties;
    private StreamingProperties streamingProperties;
    private DiarizationProperties diarizationProperties;

    @BeforeEach
    void setUp() {
        tenantProperties = new TenantProperties();
        ssoProperties = new SsoProperties();
        streamingProperties = new StreamingProperties();
        diarizationProperties = new DiarizationProperties();
    }

    private IntegrationStatusService service(SsoProvider provider) {
        return new IntegrationStatusService(tenantProperties, ssoProperties, streamingProperties,
                diarizationProperties, provider);
    }

    @Test
    void defaultsAreAllDisabled() {
        IntegrationStatusService.IntegrationsView view = service(new DisabledSsoProvider()).status();

        assertFalse(view.tenant().enabled());
        assertEquals("SINGLE", view.tenant().mode());
        assertEquals("single", view.tenant().provider());
        assertTrue(view.tenant().configured());

        assertFalse(view.sso().enabled());
        assertEquals("OIDC", view.sso().provider());
        assertFalse(view.sso().configured());
        assertEquals("", view.sso().loginUrl());

        assertFalse(view.streaming().enabled());
        assertEquals("unsupported", view.streaming().provider());
        assertFalse(view.streaming().configured());

        assertFalse(view.diarization().enabled());
        assertEquals("unsupported", view.diarization().provider());
        assertFalse(view.diarization().configured());
    }

    @Test
    void configurationIsReflectedInStatus() {
        tenantProperties.setEnabled(true);
        tenantProperties.setMode(TenantProperties.Mode.HEADER);

        ssoProperties.setEnabled(true);
        ssoProperties.setProvider(SsoProperties.Provider.LDAP);
        ssoProperties.setIssuer("ldap://idp.example.com:389");
        ssoProperties.setClientId("cn=bind,dc=example,dc=com");
        ssoProperties.setClientSecret("bind-password");

        streamingProperties.setEnabled(true);
        streamingProperties.setProvider("funasr");
        streamingProperties.setEndpoint("wss://asr.example.com/stream");
        streamingProperties.setApiKey("asr-key");

        diarizationProperties.setEnabled(true);
        diarizationProperties.setProvider("pyannote");
        diarizationProperties.setEndpoint("https://diar.example.com/v1");
        diarizationProperties.setApiKey("dia-key");

        IntegrationStatusService.IntegrationsView view = service(new DisabledSsoProvider()).status();

        assertTrue(view.tenant().enabled());
        assertEquals("HEADER", view.tenant().mode());
        assertEquals("header", view.tenant().provider());

        assertTrue(view.sso().enabled());
        assertEquals("LDAP", view.sso().provider());
        assertTrue(view.sso().configured());

        assertTrue(view.streaming().enabled());
        assertEquals("funasr", view.streaming().provider());
        assertTrue(view.streaming().configured());

        assertTrue(view.diarization().enabled());
        assertEquals("pyannote", view.diarization().provider());
        assertTrue(view.diarization().configured());
    }

    @Test
    void ssoLoginUrlComesFromActiveProvider() {
        ssoProperties.setEnabled(true);
        ssoProperties.setIssuer("https://idp.example.com");
        ssoProperties.setClientId("echolint");
        ssoProperties.setClientSecret("secret");
        SsoProvider provider = new StubSsoProvider(true, true, " https://idp.example.com/authorize ");

        IntegrationStatusService.SsoStatus status = service(provider).ssoStatus();

        assertEquals("https://idp.example.com/authorize", status.loginUrl());
        assertTrue(status.configured());
    }

    @Test
    void providerFailureDoesNotBreakStatus() {
        SsoProvider failing = new StubSsoProvider(true, true, null) {
            @Override
            public String loginUrl() {
                throw new IllegalStateException("adapter not ready");
            }
        };

        IntegrationStatusService.SsoStatus status = service(failing).ssoStatus();

        assertEquals("", status.loginUrl(), "适配器异常时按未配置处理，状态接口保持可用");
    }

    @Test
    void serializedStatusNeverContainsSecrets() throws Exception {
        ssoProperties.setEnabled(true);
        ssoProperties.setIssuer("https://idp.example.com");
        ssoProperties.setClientId("echolint");
        ssoProperties.setClientSecret("s3cr3t-client-value");
        ssoProperties.setRoleMappingJson("{\"admins\":\"ADMIN\"}");

        streamingProperties.setEnabled(true);
        streamingProperties.setProvider("funasr");
        streamingProperties.setEndpoint("wss://asr.example.com/stream");
        streamingProperties.setApiKey("asr-key-1234");

        diarizationProperties.setEnabled(true);
        diarizationProperties.setProvider("pyannote");
        diarizationProperties.setEndpoint("https://diar.example.com/v1");
        diarizationProperties.setApiKey("dia-key-5678");

        String json = new ObjectMapper().writeValueAsString(service(new DisabledSsoProvider()).status());

        assertFalse(json.contains("s3cr3t-client-value"), json);
        assertFalse(json.contains("asr-key-1234"), json);
        assertFalse(json.contains("dia-key-5678"), json);
        assertFalse(json.contains("apiKey"), json);
        assertFalse(json.contains("clientSecret"), json);
        assertFalse(json.contains("roleMapping"), json);
        assertFalse(json.contains("issuer"), "issuer/端点等内部地址也不进入状态接口");
    }

    /** 可配置的测试用 SSO 适配器 */
    private static class StubSsoProvider implements SsoProvider {

        private final boolean enabled;
        private final boolean configured;
        private final String loginUrl;

        StubSsoProvider(boolean enabled, boolean configured, String loginUrl) {
            this.enabled = enabled;
            this.configured = configured;
            this.loginUrl = loginUrl;
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public String loginUrl() {
            return loginUrl;
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of("provider", id());
        }
    }
}
