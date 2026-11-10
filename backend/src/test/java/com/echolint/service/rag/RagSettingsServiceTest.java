package com.echolint.service.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echolint.config.RagProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** RAG 运行时设置：默认值、持久化、缓存刷新、数值钳制，以及 embedding 供应商/模型的 保存、校验与 embeddingChanged 语义。 */
@ExtendWith(MockitoExtension.class)
class RagSettingsServiceTest {

    @Mock private AppSettingRepository settings;
    @Mock private RagEmbeddingSettingsResolver embeddingResolver;

    private RagProperties properties;
    private RagSettingsService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setEnabled(false);
        properties.setTopK(4);
        properties.setMinScore(0.25);
        properties.setMaxContextChars(2400);
        properties.setCorpusExamples(5);
        properties.getEmbedding().setProviderId("aliyun");
        properties.getEmbedding().setModel("qwen3.7-text-embedding");
        lenient().when(settings.findById(anyString())).thenReturn(Optional.empty());
        lenient()
                .when(embeddingResolver.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "aliyun",
                                "阿里云百炼",
                                "qwen3.7-text-embedding",
                                true,
                                RagEmbeddingSettingsResolver.SOURCE_ENV));
        service = new RagSettingsService(settings, properties, embeddingResolver);
    }

    @Test
    void defaultsComeFromPropertiesWhenNoSettingsStored() {
        RagSettingsService.Snapshot snapshot = service.current();

        assertFalse(snapshot.enabled());
        assertEquals(4, snapshot.topK());
        assertEquals(0.25, snapshot.minScore(), 1e-9);
        assertEquals(2400, snapshot.maxContextChars());
        assertEquals(5, snapshot.corpusExamples());
        assertEquals("defaults", snapshot.source());
    }

    @Test
    void readsPersistedSettingsAndNormalizesSource() {
        when(settings.findById("rag.enabled"))
                .thenReturn(Optional.of(AppSetting.of("rag.enabled", "true")));
        when(settings.findById("rag.top-k"))
                .thenReturn(Optional.of(AppSetting.of("rag.top-k", "8")));
        when(settings.findById("rag.min-score"))
                .thenReturn(Optional.of(AppSetting.of("rag.min-score", "0.4")));
        when(settings.findById("rag.max-context-chars"))
                .thenReturn(Optional.of(AppSetting.of("rag.max-context-chars", "1200")));
        when(settings.findById("rag.corpus-examples"))
                .thenReturn(Optional.of(AppSetting.of("rag.corpus-examples", "3")));

        RagSettingsService.Snapshot snapshot = service.current();

        assertTrue(snapshot.enabled());
        assertEquals(8, snapshot.topK());
        assertEquals(0.4, snapshot.minScore(), 1e-9);
        assertEquals(1200, snapshot.maxContextChars());
        assertEquals(3, snapshot.corpusExamples());
        assertEquals("settings", snapshot.source());
    }

    @Test
    void updatePersistsAllKeysAndRefreshesSnapshot() {
        RagSettingsService.UpdateOutcome outcome =
                service.update(true, 6, 0.3, 3000, 8, null, null);
        RagSettingsService.Snapshot snapshot = outcome.snapshot();

        assertTrue(snapshot.enabled());
        assertEquals(6, snapshot.topK());
        assertEquals("settings", snapshot.source());
        assertFalse(outcome.embeddingChanged(), "未提交 embedding 变更时 embeddingChanged=false");
        verify(settings, atLeastOnce()).save(any(AppSetting.class));
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings, times(5)).save(captor.capture());
        assertTrue(
                captor.getAllValues().stream()
                        .anyMatch(
                                s ->
                                        s.getSettingKey().equals("rag.enabled")
                                                && s.getSettingValue().equals("true")));
        assertTrue(
                captor.getAllValues().stream()
                        .anyMatch(
                                s ->
                                        s.getSettingKey().equals("rag.top-k")
                                                && s.getSettingValue().equals("6")));
    }

    @Test
    void updateClampsInvalidValues() {
        RagSettingsService.Snapshot snapshot =
                service.update(true, 999, 2.5, 5, -3, null, null).snapshot();

        assertEquals(20, snapshot.topK(), "topK 上限 20");
        assertEquals(1.0, snapshot.minScore(), 1e-9, "minScore 上限 1");
        assertEquals(200, snapshot.maxContextChars(), "字符预算下限 200");
        assertEquals(0, snapshot.corpusExamples(), "few-shot 条数下限 0");
    }

    @Test
    void nullFieldsKeepCurrentValues() {
        RagSettingsService.Snapshot first =
                service.update(true, 7, 0.5, 3000, 6, null, null).snapshot();
        RagSettingsService.Snapshot second =
                service.update(null, null, null, null, null, null, null).snapshot();

        assertEquals(first.topK(), second.topK());
        assertEquals(first.minScore(), second.minScore());
        assertTrue(second.enabled());
    }

    // ------------------------------------------------------------------ embedding 配置

    @Test
    void embeddingChangePersistsKeysAndReportsChanged() {
        when(embeddingResolver.requireUsableProvider(anyString()))
                .thenReturn(provider("deepseek", "DeepSeek"));

        RagSettingsService.UpdateOutcome outcome =
                service.update(null, null, null, null, null, " DeepSeek ", " text-embedding-v4 ");

        assertTrue(outcome.embeddingChanged(), "provider/model 变化应标记 embeddingChanged");
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings, times(7)).save(captor.capture());
        assertTrue(
                captor.getAllValues().stream()
                        .anyMatch(
                                s ->
                                        s.getSettingKey().equals("rag.embedding.provider")
                                                && s.getSettingValue().equals("deepseek")));
        assertTrue(
                captor.getAllValues().stream()
                        .anyMatch(
                                s ->
                                        s.getSettingKey().equals("rag.embedding.model")
                                                && s.getSettingValue()
                                                        .equals("text-embedding-v4")));
    }

    @Test
    void sameEmbeddingValuesReportNotChanged() {
        when(embeddingResolver.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "deepseek",
                                "DeepSeek",
                                "text-embedding-v4",
                                true,
                                RagEmbeddingSettingsResolver.SOURCE_DATABASE));
        when(embeddingResolver.requireUsableProvider("deepseek"))
                .thenReturn(provider("deepseek", "DeepSeek"));

        RagSettingsService.UpdateOutcome outcome =
                service.update(null, null, null, null, null, "deepseek", "text-embedding-v4");

        assertFalse(outcome.embeddingChanged(), "值未变化时 embeddingChanged=false");
    }

    @Test
    void modelOnlyChangeKeepsProviderAndReportsChanged() {
        RagSettingsService.UpdateOutcome outcome =
                service.update(null, null, null, null, null, null, "text-embedding-v3");

        assertTrue(outcome.embeddingChanged());
        verify(settings)
                .save(
                        argThat(
                                s ->
                                        s.getSettingKey().equals("rag.embedding.model")
                                                && s.getSettingValue()
                                                        .equals("text-embedding-v3")));
        verify(settings, never())
                .save(argThat(s -> s.getSettingKey().equals("rag.embedding.provider")));
    }

    @Test
    void blankEmbeddingFieldsResetToEnvDefaults() {
        when(embeddingResolver.resolve())
                .thenReturn(
                        new RagEmbeddingSettingsResolver.Resolved(
                                "deepseek",
                                "DeepSeek",
                                "db-model",
                                true,
                                RagEmbeddingSettingsResolver.SOURCE_DATABASE));

        RagSettingsService.UpdateOutcome outcome =
                service.update(null, null, null, null, null, "", "");

        assertTrue(outcome.embeddingChanged(), "回退 env 默认值也算配置变化");
        verify(settings)
                .save(
                        argThat(
                                s ->
                                        s.getSettingKey().equals("rag.embedding.provider")
                                                && s.getSettingValue().isEmpty()));
        verify(settings)
                .save(
                        argThat(
                                s ->
                                        s.getSettingKey().equals("rag.embedding.model")
                                                && s.getSettingValue().isEmpty()));
    }

    @Test
    void unknownEmbeddingProviderRejectedBeforeAnySave() {
        when(embeddingResolver.requireUsableProvider("ghost"))
                .thenThrow(new BizException("未知的 embedding 供应商: ghost"));

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> service.update(null, null, null, null, null, "ghost", null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(settings, never()).save(any(AppSetting.class));
    }

    @Test
    void tooLongEmbeddingModelRejectedBeforeAnySave() {
        String model = "x".repeat(RagEmbeddingSettingsResolver.MAX_MODEL_LENGTH + 1);

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> service.update(null, null, null, null, null, null, model));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("长度不能超过"), error.getMessage());
        verify(settings, never()).save(any(AppSetting.class));
    }

    // ------------------------------------------------------------------ 其他

    @Test
    void lastReindexAtIsReadable() {
        when(settings.findById("rag.last-reindex-at"))
                .thenReturn(
                        Optional.of(AppSetting.of("rag.last-reindex-at", "2026-10-20T21:00:00")));

        assertEquals("2026-10-20T21:00:00", service.lastReindexAt().orElseThrow());
    }

    @Test
    void markReindexedWritesTimestamp() {
        service.markReindexedNow();

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings).save(captor.capture());
        assertEquals("rag.last-reindex-at", captor.getValue().getSettingKey());
        assertFalse(captor.getValue().getSettingValue().isBlank());
    }

    private ResolvedProvider provider(String key, String label) {
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
                true,
                true,
                Map.of(),
                key.toUpperCase() + "_API_KEY",
                "****-key");
    }
}
