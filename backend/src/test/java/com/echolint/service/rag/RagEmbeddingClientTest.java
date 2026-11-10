package com.echolint.service.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.echolint.config.RagProperties;
import com.echolint.service.AiInvocationService;
import com.echolint.service.AiQuotaService;
import com.echolint.service.ProviderRegistry;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * embedding 客户端使用解析后的供应商/模型（设置页可切换）：配置视图、可用性与 解析结果错误时的提示均以 {@link RagEmbeddingSettingsResolver}
 * 为准（不发起网络）。
 */
@ExtendWith(MockitoExtension.class)
class RagEmbeddingClientTest {

    @Mock private ProviderRegistry providers;
    @Mock private AiInvocationService invocations;
    @Mock private AiQuotaService quotaService;
    @Mock private RagEmbeddingSettingsResolver embeddingSettings;

    private RagProperties properties;
    private RagEmbeddingClient client;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getEmbedding().setProviderId("aliyun");
        properties.getEmbedding().setModel("qwen3.7-text-embedding");
        client =
                new RagEmbeddingClient(
                        providers,
                        properties,
                        new ObjectMapper(),
                        invocations,
                        quotaService,
                        embeddingSettings);
    }

    @Test
    void infoUsesResolvedProviderModelSourceAndCandidates() {
        lenient()
                .when(embeddingSettings.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "deepseek",
                                "DeepSeek",
                                "db-embedding-model",
                                true,
                                RagEmbeddingSettingsResolver.SOURCE_DATABASE));
        lenient()
                .when(embeddingSettings.candidateModels("db-embedding-model"))
                .thenReturn(List.of("qwen3.7-text-embedding", "db-embedding-model"));

        RagEmbeddingClient.EmbeddingInfo info = client.info();

        assertEquals("deepseek", info.providerId());
        assertEquals("DeepSeek", info.providerLabel());
        assertEquals("db-embedding-model", info.model(), "模型来自解析器（DB 优先）");
        assertEquals("/embeddings", info.path(), "路径仍由 RagProperties 决定");
        assertEquals(1024, info.dimensions());
        assertEquals(30, info.timeoutSeconds());
        assertTrue(info.apiKeyConfigured());
        assertEquals("DATABASE", info.source());
        assertEquals(List.of("qwen3.7-text-embedding", "db-embedding-model"), info.models());
    }

    @Test
    void availableRequiresEnabledProviderWithKeyAndBaseUrl() {
        when(embeddingSettings.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "aliyun", "阿里云百炼", "m", true, "DATABASE"));
        lenient()
                .when(providers.find("aliyun"))
                .thenReturn(Optional.of(provider("aliyun", true, "sk-key")));

        assertTrue(client.available());

        lenient()
                .when(providers.find("aliyun"))
                .thenReturn(Optional.of(provider("aliyun", false, "sk-key")));
        assertFalse(client.available(), "停用的供应商不可用");
    }

    @Test
    void testFailureUsesResolvedProviderAndModelWithoutNetwork() {
        when(embeddingSettings.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "ghost", "ghost", "db-model", false, "ENV"));
        lenient().when(providers.find("ghost")).thenReturn(Optional.empty());

        RagEmbeddingClient.TestResult result = client.test();

        assertFalse(result.ok());
        assertEquals("ghost", result.providerId());
        assertEquals("db-model", result.model(), "错误结果也应带解析后的模型");
        assertTrue(result.message().contains("ghost"), result.message());
    }

    @Test
    void emptyEmbeddingInputIsRejected() {
        RagEmbeddingException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        RagEmbeddingException.class,
                        () -> client.embed(List.of(), null, AiInvocationService.STAGE_EMBED_INDEX));

        assertTrue(error.getMessage().contains("输入为空"), error.getMessage());
    }

    private ResolvedProvider provider(String key, boolean enabled, String apiKey) {
        return new ResolvedProvider(
                key,
                "阿里云百炼",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "/embeddings",
                apiKey,
                null,
                List.of(),
                false,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                enabled,
                true,
                Map.of(),
                "DASHSCOPE_API_KEY",
                "****-key");
    }
}
