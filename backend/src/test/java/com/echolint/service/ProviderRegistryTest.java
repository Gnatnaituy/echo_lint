package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.config.SecretCipher;
import com.echolint.entity.AiProvider;
import com.echolint.repository.AiProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 供应商注册表（Batch 3a）：yml 启动导入（加密/掩码/不覆盖不删除）、DB 优先 + yml 兜底、
 * 解密失败降级为未配置、价格与候选模型快照。
 */
@ExtendWith(MockitoExtension.class)
class ProviderRegistryTest {

    @Mock
    private AiProviderRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretCipher cipher = new SecretCipher("test-secret");
    private ScreenModelProperties properties;
    private ProviderRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new ScreenModelProperties();
        ScreenModelProperties.Provider openai = provider("openai", "OpenAI", "https://api.openai.com",
                "sk-openai-1234", "gpt-4o-mini", List.of("gpt-4o-mini", "gpt-4o"));
        openai.setInputPricePer1k(new BigDecimal("0.01"));
        openai.setOutputPricePer1k(new BigDecimal("0.02"));
        ScreenModelProperties.Provider deepseek = provider("deepseek", "DeepSeek", "https://api.deepseek.com",
                "", "deepseek-flash", List.of("deepseek-flash"));
        properties.setProviders(List.of(openai, deepseek));
        properties.setDefaultProvider("openai");
        registry = new ProviderRegistry(repository, properties, cipher, objectMapper);
    }

    private ScreenModelProperties.Provider provider(String id, String label, String baseUrl, String apiKey,
                                                    String defaultModel, List<String> models) {
        ScreenModelProperties.Provider p = new ScreenModelProperties.Provider();
        p.setId(id);
        p.setLabel(label);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setApiKeyEnv(id.toUpperCase() + "_API_KEY");
        p.setChatPath("/v1/chat/completions");
        p.setDefaultModel(defaultModel);
        p.setModels(models);
        p.setJsonMode(true);
        return p;
    }

    private AiProvider entity(String key, boolean builtIn, boolean enabled, String plainApiKey) {
        AiProvider row = new AiProvider();
        row.setProviderKey(key);
        row.setLabel("DB " + key);
        row.setBaseUrl("https://db.example.com/");
        row.setChatPath("/chat/completions");
        row.setApiKeyCipher(plainApiKey == null ? null : cipher.encrypt(plainApiKey));
        row.setApiKeyMasked(cipher.mask(plainApiKey));
        row.setDefaultModel("db-model");
        row.setModelsJson("[\"db-model\",\"db-model-pro\"]");
        row.setJsonMode(true);
        row.setInputPricePer1k(new BigDecimal("0.1"));
        row.setOutputPricePer1k(new BigDecimal("0.2"));
        row.setEnabled(enabled);
        row.setBuiltIn(builtIn);
        return row;
    }

    // ------------------------------------------------------------------ 启动导入

    @Test
    void seedsMissingProvidersWithEncryptedKeyAndMask() {
        when(repository.existsByProviderKeyIgnoreCase(anyString())).thenReturn(false);
        when(repository.save(any(AiProvider.class))).thenAnswer(inv -> inv.getArgument(0));

        int seeded = registry.seedFromConfig();

        assertEquals(2, seeded);
        ArgumentCaptor<AiProvider> captor = ArgumentCaptor.forClass(AiProvider.class);
        verify(repository, times(2)).save(captor.capture());
        AiProvider openai = captor.getAllValues().stream()
                .filter(p -> "openai".equals(p.getProviderKey())).findFirst().orElseThrow();
        assertTrue(openai.isBuiltIn());
        assertTrue(openai.isEnabled());
        assertNotNull(openai.getApiKeyCipher());
        assertFalse(openai.getApiKeyCipher().contains("sk-openai-1234"), "落库必须是密文");
        assertEquals("sk-openai-1234", cipher.decrypt(openai.getApiKeyCipher()));
        assertEquals("****1234", openai.getApiKeyMasked());
        assertTrue(openai.getModelsJson().contains("gpt-4o-mini"));
        assertEquals(0, new BigDecimal("0.01").compareTo(openai.getInputPricePer1k()));

        AiProvider deepseek = captor.getAllValues().stream()
                .filter(p -> "deepseek".equals(p.getProviderKey())).findFirst().orElseThrow();
        assertNull(deepseek.getApiKeyCipher(), "未配置密钥的供应商只落空密文");
        assertNull(deepseek.getApiKeyMasked());
    }

    @Test
    void seedDoesNotTouchExistingProviders() {
        when(repository.existsByProviderKeyIgnoreCase("openai")).thenReturn(true);
        when(repository.existsByProviderKeyIgnoreCase("deepseek")).thenReturn(true);

        int seeded = registry.seedFromConfig();

        assertEquals(0, seeded);
        verify(repository, never()).save(any(AiProvider.class));
        verify(repository, never()).delete(any(AiProvider.class));
    }

    @Test
    void seedNeverDeletesCustomRows() {
        when(repository.existsByProviderKeyIgnoreCase("openai")).thenReturn(false);
        when(repository.existsByProviderKeyIgnoreCase("deepseek")).thenReturn(true);
        when(repository.save(any(AiProvider.class))).thenAnswer(inv -> inv.getArgument(0));

        registry.seedFromConfig();

        verify(repository, never()).delete(any(AiProvider.class));
        verify(repository, never()).deleteAll();
    }

    // ------------------------------------------------------------------ 解析

    @Test
    void resolvesDatabaseFirstThenYmlFallback() {
        when(repository.findAllByOrderByBuiltInDescProviderKeyAsc())
                .thenReturn(List.of(entity("openai", true, true, "sk-db-key"), entity("custom-a", false, true, null)));

        List<ProviderRegistry.ResolvedProvider> all = registry.all();

        assertEquals(3, all.size(), "DB 两行 + yml 独有 deepseek 兜底");
        ProviderRegistry.ResolvedProvider openai = all.stream()
                .filter(p -> "openai".equals(p.providerKey())).findFirst().orElseThrow();
        assertEquals("DB openai", openai.displayLabel(), "DB 行优先于 yml");
        assertEquals("https://db.example.com", openai.baseUrl(), "应去掉末尾斜杠");
        assertEquals("sk-db-key", openai.apiKey(), "密钥应解密");
        assertEquals("OPENAI_API_KEY", openai.apiKeyEnv(), "extra-body/env 等 yml 字段按 key 合并");
        assertTrue(openai.builtIn());
        assertTrue(all.stream().anyMatch(p -> "deepseek".equals(p.providerKey())));
        assertTrue(all.stream().anyMatch(p -> "custom-a".equals(p.providerKey())));
    }

    @Test
    void findIsCaseInsensitiveAndPrefersDatabase() {
        when(repository.findByProviderKeyIgnoreCase("openai"))
                .thenReturn(Optional.of(entity("openai", true, true, "sk-db")));
        when(repository.findByProviderKeyIgnoreCase("deepseek")).thenReturn(Optional.empty());
        when(repository.findByProviderKeyIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertTrue(registry.find("OPENAI").orElseThrow().builtIn());
        assertEquals("https://api.deepseek.com",
                registry.find("deepseek").orElseThrow().baseUrl(), "yml 兜底");
        assertTrue(registry.find("ghost").isEmpty());
        assertTrue(registry.find(" ").isEmpty());
    }

    @Test
    void undecryptableKeyIsTreatedAsNotConfigured() {
        AiProvider row = entity("openai", true, true, null);
        row.setApiKeyCipher(new SecretCipher("another-master-key").encrypt("sk-old"));
        when(repository.findByProviderKeyIgnoreCase("openai")).thenReturn(Optional.of(row));

        ProviderRegistry.ResolvedProvider resolved = registry.find("openai").orElseThrow();

        assertFalse(resolved.apiKeyConfigured(), "主密钥变更后旧密文按未配置处理，不影响启动");
        assertNull(resolved.apiKey());
    }

    @Test
    void snapshotsExposeMaskOnly() {
        when(repository.findAllByOrderByBuiltInDescProviderKeyAsc())
                .thenReturn(List.of(entity("openai", true, true, "sk-db-key-9876")));

        ProviderRegistry.ProviderSnapshot openai = registry.snapshots().stream()
                .filter(p -> "openai".equals(p.key())).findFirst().orElseThrow();

        assertEquals("****9876", openai.apiKeyMasked());
        assertTrue(openai.apiKeyConfigured());
        assertTrue(openai.builtIn());
        assertEquals(List.of("db-model", "db-model-pro"), openai.models());
        assertEquals(0, new BigDecimal("0.1").compareTo(openai.inputPricePer1k()));
    }
}
