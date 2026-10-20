package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.AiScreenResult.CorpusExample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 检索服务：开关/空输入/降级、语料 few-shot 回退与检索预览失败语义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagRetrievalServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RagEmbeddingClient embeddingClient;
    @Mock private RagSettingsService settings;
    @Mock private CorpusEntryRepository corpusEntries;

    private RagProperties properties;
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        when(settings.isEnabled()).thenReturn(true);
        when(settings.current()).thenReturn(
                new RagSettingsService.Snapshot(true, 4, 0.25, 2400, 5, "settings"));
        service = new RagRetrievalService(jdbcTemplate, embeddingClient, settings, properties, corpusEntries);
    }

    @Test
    void disabledRagReturnsDisabledWithoutAnyCall() {
        when(settings.isEnabled()).thenReturn(false);

        RagContext context = service.knowledgeContext("一些文本", 1L);

        assertFalse(context.enabled());
        assertEquals(RagContext.STATUS_DISABLED, context.status());
        assertFalse(context.hasReferences());
        verifyNoInteractions(embeddingClient, jdbcTemplate);
    }

    @Test
    void blankQueryReturnsEmpty() {
        RagContext context = service.knowledgeContext("   ", 1L);

        assertEquals(RagContext.STATUS_EMPTY, context.status());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void embeddingFailureDegradesWithoutThrowing() {
        when(embeddingClient.embedQuery(anyString(), eq(1L)))
                .thenThrow(new RagEmbeddingException("embedding down"));

        RagContext context = service.knowledgeContext("query", 1L);

        assertTrue(context.enabled());
        assertEquals(RagContext.STATUS_DEGRADED, context.status());
        assertEquals("embedding down", context.error());
        assertFalse(context.hasReferences());
    }

    @Test
    void corpusExamplesFallBackToLatestWhenDisabled() {
        when(settings.isEnabled()).thenReturn(false);
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.VIOLATION), any(Pageable.class)))
                .thenReturn(List.of(corpus(1L, CorpusLabel.VIOLATION, "违规文本")));
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.COMPLIANT), any(Pageable.class)))
                .thenReturn(List.of(corpus(2L, CorpusLabel.COMPLIANT, "合规文本")));

        List<CorpusExample> examples = service.corpusExamples("待审文本", 4);

        assertEquals(2, examples.size());
        assertEquals(CorpusLabel.VIOLATION, examples.get(0).label());
        assertEquals("违规文本", examples.get(0).transcript());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void corpusExamplesFallBackWhenEmbeddingFails() {
        when(embeddingClient.embedQuery(anyString(), any()))
                .thenThrow(new RagEmbeddingException("down"));
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.VIOLATION), any(Pageable.class)))
                .thenReturn(List.of(corpus(1L, CorpusLabel.VIOLATION, "违规文本")));
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.COMPLIANT), any(Pageable.class)))
                .thenReturn(List.of());

        List<CorpusExample> examples = service.corpusExamples("待审文本", 4);

        assertFalse(examples.isEmpty());
        assertEquals("违规文本", examples.get(0).transcript());
    }

    @Test
    void corpusExamplesTruncateLongTranscript() {
        when(settings.isEnabled()).thenReturn(false);
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.VIOLATION), any(Pageable.class)))
                .thenReturn(List.of(corpus(1L, CorpusLabel.VIOLATION, "x".repeat(1000))));
        when(corpusEntries.findByLabelOrderByIdDesc(eq(CorpusLabel.COMPLIANT), any(Pageable.class)))
                .thenReturn(List.of());

        List<CorpusExample> examples = service.corpusExamples("待审", 2);

        assertEquals(RagRetrievalService.CORPUS_EXAMPLE_CHARS, examples.get(0).transcript().length());
    }

    @Test
    void zeroExamplesSkipsEverything() {
        assertTrue(service.corpusExamples("文本", 0).isEmpty());
        verifyNoInteractions(embeddingClient, corpusEntries);
    }

    @Test
    void searchRejectsBlankQuery() {
        assertThrows(BizException.class, () -> service.search(" ", 4));
    }

    @Test
    void searchWrapsEmbeddingFailureAsBizException() {
        when(embeddingClient.embedQuery(anyString(), any()))
                .thenThrow(new RagEmbeddingException("down"));

        BizException error = assertThrows(BizException.class, () -> service.search("query", 4));
        assertTrue(error.getMessage().contains("检索失败"));
    }

    private static CorpusEntry corpus(long id, CorpusLabel label, String transcript) {
        CorpusEntry entry = new CorpusEntry();
        entry.setId(id);
        entry.setLabel(label);
        entry.setTranscript(transcript);
        return entry;
    }
}
