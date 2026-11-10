package com.echolint.service.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.echolint.config.RagProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** embedding 供应商/模型解析：DB 优先、env 兜底、source 语义、保存校验与候选模型。 */
@ExtendWith(MockitoExtension.class)
class RagEmbeddingSettingsResolverTest {

    @Mock private AppSettingRepository settings;
    @Mock private ProviderRegistry providers;

    private final Map<String, AppSetting> store = new HashMap<>();
    private RagProperties properties;
    private RagEmbeddingSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        store.clear();
        lenient()
                .when(settings.findById(anyString()))
                .thenAnswer(
                        inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));

        properties = new RagProperties();
        properties.getEmbedding().setProviderId("aliyun");
        properties.getEmbedding().setModel("qwen3.7-text-embedding");

        lenient()
                .when(providers.find("aliyun"))
                .thenReturn(Optional.of(provider("aliyun", "阿里云百炼", "sk-aliyun")));
        lenient()
                .when(providers.find("deepseek"))
                .thenReturn(Optional.of(provider("deepseek", "DeepSeek", "sk-deepseek")));
        lenient()
                .when(providers.find("disabled"))
                .thenReturn(Optional.of(disabledProvider("disabled", "停用供应商")));
        lenient()
                .when(providers.find("no-key"))
                .thenReturn(Optional.of(provider("no-key", "无密钥供应商", null)));

        resolver = new RagEmbeddingSettingsResolver(settings, providers, properties);
    }

    // ------------------------------------------------------------------ 解析

    @Test
    void envDefaultsWhenNothingStored() {
        RagEmbeddingSettingsResolver.Resolved resolved = resolver.resolve();

        assertEquals("aliyun", resolved.providerId());
        assertEquals("阿里云百炼", resolved.providerLabel());
        assertEquals("qwen3.7-text-embedding", resolved.model());
        assertTrue(resolved.apiKeyConfigured());
        assertEquals(RagEmbeddingSettingsResolver.SOURCE_ENV, resolved.source());
    }

    @Test
    void databaseValuesWinOverEnv() {
        store.put(
                RagEmbeddingSettingsResolver.KEY_PROVIDER,
                AppSetting.of(RagEmbeddingSettingsResolver.KEY_PROVIDER, " DeepSeek "));
        store.put(
                RagEmbeddingSettingsResolver.KEY_MODEL,
                AppSetting.of(RagEmbeddingSettingsResolver.KEY_MODEL, " text-embedding-v4 "));

        RagEmbeddingSettingsResolver.Resolved resolved = resolver.resolve();

        assertEquals("deepseek", resolved.providerId(), "供应商标识归一化为小写");
        assertEquals("DeepSeek", resolved.providerLabel());
        assertEquals("text-embedding-v4", resolved.model());
        assertEquals(RagEmbeddingSettingsResolver.SOURCE_DATABASE, resolved.source());
    }

    @Test
    void blankDatabaseValueFallsBackToEnv() {
        store.put(
                RagEmbeddingSettingsResolver.KEY_PROVIDER,
                AppSetting.of(RagEmbeddingSettingsResolver.KEY_PROVIDER, "  "));
        store.put(
                RagEmbeddingSettingsResolver.KEY_MODEL,
                AppSetting.of(RagEmbeddingSettingsResolver.KEY_MODEL, "db-model"));

        RagEmbeddingSettingsResolver.Resolved resolved = resolver.resolve();

        assertEquals("aliyun", resolved.providerId(), "空白 DB 值回退 env 默认");
        assertEquals("db-model", resolved.model());
        assertEquals(RagEmbeddingSettingsResolver.SOURCE_DATABASE, resolved.source(), "至少一项来自数据库");
    }

    @Test
    void unknownProviderKeepsIdAsLabelAndNotConfigured() {
        properties.getEmbedding().setProviderId("ghost");

        RagEmbeddingSettingsResolver.Resolved resolved = resolver.resolve();

        assertEquals("ghost", resolved.providerId());
        assertEquals("ghost", resolved.providerLabel());
        assertFalse(resolved.apiKeyConfigured());
    }

    @Test
    void readFailureFallsBackToEnvDefaults() {
        settings = org.mockito.Mockito.mock(AppSettingRepository.class);
        org.mockito.Mockito.when(settings.findById(anyString()))
                .thenThrow(new RuntimeException("db down"));
        RagEmbeddingSettingsResolver failing =
                new RagEmbeddingSettingsResolver(settings, providers, properties);

        RagEmbeddingSettingsResolver.Resolved resolved = failing.resolve();

        assertEquals("aliyun", resolved.providerId());
        assertEquals(RagEmbeddingSettingsResolver.SOURCE_ENV, resolved.source());
    }

    // ------------------------------------------------------------------ 保存校验

    @Test
    void requireUsableProviderRejectsUnknownDisabledAndNoKey() {
        BizException unknown =
                assertThrows(BizException.class, () -> resolver.requireUsableProvider("missing"));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatus());
        assertTrue(unknown.getMessage().contains("未知的 embedding 供应商"), unknown.getMessage());

        BizException disabled =
                assertThrows(BizException.class, () -> resolver.requireUsableProvider("disabled"));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatus());
        assertTrue(disabled.getMessage().contains("已停用"), disabled.getMessage());

        BizException noKey =
                assertThrows(BizException.class, () -> resolver.requireUsableProvider("no-key"));
        assertEquals(HttpStatus.BAD_REQUEST, noKey.getStatus());
        assertTrue(noKey.getMessage().contains("未配置 API Key"), noKey.getMessage());
    }

    @Test
    void requireUsableProviderReturnsNormalizedProvider() {
        ResolvedProvider provider = resolver.requireUsableProvider(" ALIYUN ");

        assertEquals("aliyun", provider.providerKey());
        assertEquals("阿里云百炼", provider.displayLabel());
    }

    @Test
    void candidateModelsDedupAndIncludeCurrent() {
        properties
                .getEmbedding()
                .setModels(
                        List.of(
                                "qwen3.7-text-embedding",
                                "text-embedding-v4",
                                "text-embedding-v4",
                                " "));

        List<String> models = resolver.candidateModels("custom-embedding");

        assertEquals(
                List.of("qwen3.7-text-embedding", "text-embedding-v4", "custom-embedding"), models);
    }

    @Test
    void normalizeProviderIdLowercasesAndTrims() {
        assertEquals("aliyun", RagEmbeddingSettingsResolver.normalizeProviderId(" ALIYUN "));
        assertEquals("", RagEmbeddingSettingsResolver.normalizeProviderId(null));
    }

    // ------------------------------------------------------------------ helpers

    private ResolvedProvider provider(String key, String label, String apiKey) {
        return new ResolvedProvider(
                key,
                label,
                "https://example.com/v1",
                "/embeddings",
                apiKey,
                null,
                List.of(),
                false,
                null,
                new BigDecimal("0.000001"),
                BigDecimal.ZERO,
                true,
                true,
                Map.of(),
                key.toUpperCase() + "_API_KEY",
                apiKey == null
                        ? null
                        : "****" + apiKey.substring(Math.max(0, apiKey.length() - 4)));
    }

    private ResolvedProvider disabledProvider(String key, String label) {
        return new ResolvedProvider(
                key,
                label,
                "https://example.com/v1",
                "/embeddings",
                "sk-key",
                null,
                List.of(),
                false,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                Map.of(),
                null,
                "****-key");
    }
}
