package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.ScreenModelProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * AI 复筛模型切换：默认值解析、落库优先级、切换校验、密钥缺失提示
 */
@ExtendWith(MockitoExtension.class)
class ScreenModelServiceTest {

    @Mock
    private AppSettingRepository settingRepository;

    /** 内存版 app_settings，模拟持久化 */
    private final Map<String, AppSetting> store = new HashMap<>();

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
        service = new ScreenModelService(properties(), settingRepository, new ObjectMapper());
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
}
