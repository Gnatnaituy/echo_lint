package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.config.SecretCipher;
import com.echolint.entity.AiProvider;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AiProviderRepository;
import com.echolint.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AI 复筛模型切换：默认值解析、落库优先级、切换校验（未知/停用）、密钥缺失提示、
 * 数据库供应商优先 + yml 兜底（Batch 3a）。
 */
@ExtendWith(MockitoExtension.class)
class ScreenModelServiceTest {

    @Mock
    private AppSettingRepository settingRepository;
    @Mock
    private AiProviderRepository providerRepository;

    /** 内存版 app_settings，模拟持久化 */
    private final Map<String, AppSetting> store = new HashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretCipher cipher = new SecretCipher("test-secret");
    private ScreenModelService service;

    @BeforeEach
    void setUp() {
        store.clear();
        lenient().when(settingRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        lenient().when(settingRepository.save(any(AppSetting.class))).thenAnswer(inv -> {
            AppSetting s = inv.getArgument(0);
            store.put(s.getSettingKey(), s);
            return s;
        });
        lenient().when(providerRepository.findAllByOrderByBuiltInDescProviderKeyAsc()).thenReturn(List.of());
        lenient().when(providerRepository.findByProviderKeyIgnoreCase(anyString())).thenReturn(Optional.empty());

        ProviderRegistry registry = new ProviderRegistry(providerRepository, properties(), cipher, objectMapper);
        service = new ScreenModelService(properties(), settingRepository, objectMapper, registry);
    }

    private ScreenModelProperties properties() {
        ScreenModelProperties p = new ScreenModelProperties();
        p.setDefaultProvider("openai");
        p.setFewShotCount(5);
        p.setRequestTimeoutSeconds(30);
        p.setProviders(List.of(
                provider("openai", "OpenAI", "https://api.openai.com", "sk-openai",
                        "/v1/chat/completions", "gpt-4o-mini",
                        List.of("gpt-4o-mini", "gpt-4o"), true),
                provider("deepseek", "DeepSeek", "https://api.deepseek.com", "",
                        "/chat/completions", "deepseek-flash",
                        List.of("deepseek-flash", "deepseek-v4-pro"), true)));
        return p;
    }

    private ScreenModelProperties.Provider provider(String id, String label, String baseUrl, String apiKey,
                                                    String chatPath, String defaultModel,
                                                    List<String> models, boolean jsonMode) {
        ScreenModelProperties.Provider p = new ScreenModelProperties.Provider();
        p.setId(id);
        p.setLabel(label);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setApiKeyEnv(id.toUpperCase() + "_API_KEY");
        p.setChatPath(chatPath);
        p.setDefaultModel(defaultModel);
        p.setModels(models);
        p.setJsonMode(jsonMode);
        return p;
    }

    private AiProvider dbProvider(String key, String apiKey, boolean enabled) {
        AiProvider row = new AiProvider();
        row.setProviderKey(key);
        row.setLabel("DB " + key);
        row.setBaseUrl("https://db.example.com");
        row.setChatPath("/v1/chat/completions");
        row.setApiKeyCipher(apiKey == null ? null : cipher.encrypt(apiKey));
        row.setApiKeyMasked(cipher.mask(apiKey));
        row.setDefaultModel("db-model");
        row.setModelsJson("[\"db-model\",\"db-model-pro\"]");
        row.setJsonMode(true);
        row.setInputPricePer1k(new BigDecimal("0.010000"));
        row.setOutputPricePer1k(new BigDecimal("0.020000"));
        row.setEnabled(enabled);
        row.setBuiltIn(false);
        return row;
    }

    @Test
    void fallsBackToConfigDefaultWhenNothingPersisted() {
        ScreenModelService.ActiveModel active = service.active();

        assertEquals("openai", active.providerId());
        assertEquals("OpenAI", active.providerLabel());
        assertEquals("gpt-4o-mini", active.model());
        assertTrue(active.apiKeyConfigured());
        assertTrue(active.jsonMode());
        assertEquals("/v1/chat/completions", active.chatPath());

        ScreenModelService.View view = service.snapshot();
        assertEquals("DEFAULT", view.source());
        assertEquals("openai", view.providerId());
        assertEquals(2, view.providers().size());
    }

    @Test
    void persistedSelectionWinsOverConfigDefault() {
        store.put("screen.provider", AppSetting.of("screen.provider", "deepseek"));
        store.put("screen.model", AppSetting.of("screen.model", "deepseek-v4-pro"));

        ScreenModelService.ActiveModel active = service.active();

        assertEquals("deepseek", active.providerId());
        assertEquals("deepseek-v4-pro", active.model());
        assertFalse(active.apiKeyConfigured(), "fixture 中 DeepSeek 未配密钥");
        assertEquals("/chat/completions", active.chatPath());

        ScreenModelService.View view = service.snapshot();
        assertEquals("DATABASE", view.source());
        assertEquals(1, view.providers().stream().filter(ScreenModelService.ProviderView::active).count());
        assertTrue(view.providers().stream()
                .filter(ScreenModelService.ProviderView::active)
                .allMatch(v -> "deepseek".equals(v.id()) && "deepseek-v4-pro".equals(v.model())));
    }

    @Test
    void databaseProviderWinsOverConfigAndCarriesPricing() {
        when(providerRepository.findByProviderKeyIgnoreCase("openai"))
                .thenReturn(Optional.of(dbProvider("openai", "sk-db-key-1234", true)));
        store.put("screen.provider", AppSetting.of("screen.provider", "openai"));
        store.put("screen.model", AppSetting.of("screen.model", "db-model-pro"));

        ScreenModelService.ActiveModel active = service.active();

        assertEquals("DB openai", active.providerLabel(), "DB 行优先于 yml");
        assertEquals("https://db.example.com", active.baseUrl());
        assertEquals("db-model-pro", active.model());
        assertTrue(active.apiKeyConfigured(), "密钥应从密文解密");
        assertEquals(0, new BigDecimal("0.01").compareTo(active.inputPricePer1k()));
        assertEquals(0, new BigDecimal("0.02").compareTo(active.outputPricePer1k()));
    }

    @Test
    void switchPersistsProviderAndResolvesDefaultModel() {
        ScreenModelService.View view = service.switchTo("deepseek", null);

        assertEquals("deepseek", view.providerId());
        assertEquals("deepseek-flash", view.model(), "未指定模型时应回退到该供应商的 default-model");
        assertEquals("DATABASE", view.source());
        assertEquals("deepseek", store.get("screen.provider").getSettingValue());
        assertEquals("deepseek-flash", store.get("screen.model").getSettingValue());

        // 切换立即对后续解析生效
        assertEquals("deepseek-flash", service.active().model());
    }

    @Test
    void switchAcceptsModelOutsideTheCandidateList() {
        service.switchTo("deepseek", "deepseek-next-gen");

        assertEquals("deepseek-next-gen", service.active().model());
    }

    @Test
    void switchRejectsUnknownProvider() {
        BizException e = assertThrows(BizException.class, () -> service.switchTo("gemini", "x"));

        assertTrue(e.getMessage().contains("未知的模型供应商"));
        assertTrue(store.isEmpty(), "校验失败不应写入任何设置");
    }

    @Test
    void switchRejectsDisabledProvider() {
        when(providerRepository.findByProviderKeyIgnoreCase("legacy"))
                .thenReturn(Optional.of(dbProvider("legacy", "sk-legacy", false)));

        BizException e = assertThrows(BizException.class, () -> service.switchTo("legacy", "db-model"));

        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
        assertTrue(store.isEmpty(), "校验失败不应写入任何设置");
    }

    @Test
    void requireActiveExplainsMissingApiKey() {
        service.switchTo("deepseek", null);

        BizException e = assertThrows(BizException.class, () -> service.requireActive());

        assertTrue(e.getMessage().contains("DeepSeek"), e.getMessage());
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("系统设置"), e.getMessage());
        // active() 本身不校验密钥，供设置页展示状态
        assertFalse(service.active().apiKeyConfigured());
    }

    @Test
    void vanishedProviderFallsBackToDefault() {
        store.put("screen.provider", AppSetting.of("screen.provider", "ghost-provider"));
        store.put("screen.model", AppSetting.of("screen.model", "ghost-model"));

        ScreenModelService.ActiveModel active = service.active();

        assertEquals("openai", active.providerId(), "配置里已删除的供应商应回退到默认");
        assertEquals("gpt-4o-mini", active.model(), "旧模型名一并作废，回退到默认供应商的模型");
        assertEquals("DEFAULT", service.snapshot().source(), "回退后的选择不再算作界面切换");
    }

    @Test
    void disabledStoredProviderFallsBackToDefault() {
        when(providerRepository.findByProviderKeyIgnoreCase("legacy"))
                .thenReturn(Optional.of(dbProvider("legacy", "sk-legacy", false)));
        store.put("screen.provider", AppSetting.of("screen.provider", "legacy"));
        store.put("screen.model", AppSetting.of("screen.model", "db-model"));

        ScreenModelService.ActiveModel active = service.active();

        assertEquals("openai", active.providerId(), "已停用的供应商不应继续生效");
        assertEquals("gpt-4o-mini", active.model());
    }
}
