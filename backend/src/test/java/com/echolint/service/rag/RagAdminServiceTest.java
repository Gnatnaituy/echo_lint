package com.echolint.service.rag;

import com.echolint.domain.RagIndexStatus;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RAG 管理端：索引统计、视图、重建 scope 与 pgvector 可用性检查。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagAdminServiceTest {

    @Mock private RagSettingsService settings;
    @Mock private RagEmbeddingClient embeddingClient;
    @Mock private RagIndexService indexService;
    @Mock private KnowledgeEntryRepository knowledgeEntries;
    @Mock private CorpusEntryRepository corpusEntries;
    @Mock private JdbcTemplate jdbcTemplate;

    private RagAdminService service;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(
                new RagSettingsService.Snapshot(true, 4, 0.25, 2400, 5, "settings"));
        when(embeddingClient.info()).thenReturn(new RagEmbeddingClient.EmbeddingInfo(
                "openai", "OpenAI", "text-embedding-3-small", "/v1/embeddings", 1536, 30, true));
        when(knowledgeEntries.count()).thenReturn(10L);
        when(knowledgeEntries.countByIndexStatus(RagIndexStatus.READY.name())).thenReturn(7L);
        when(knowledgeEntries.countByIndexStatus(RagIndexStatus.FAILED.name())).thenReturn(1L);
        when(corpusEntries.count()).thenReturn(20L);
        when(corpusEntries.countByEmbeddingTextIsNotNull()).thenReturn(18L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(settings.lastReindexAt()).thenReturn(java.util.Optional.of("2026-10-20T20:00:00"));
        service = new RagAdminService(settings, embeddingClient, indexService, knowledgeEntries,
                corpusEntries, jdbcTemplate);
    }

    @Test
    void viewAggregatesIndexStatistics() {
        RagAdminService.RagView view = service.view();

        assertTrue(view.enabled());
        assertEquals("settings", view.source());
        assertEquals("text-embedding-3-small", view.embedding().model());
        assertTrue(view.embedding().apiKeyConfigured());
        assertEquals(4, view.retrieval().topK());
        assertEquals(10, view.index().knowledgeTotal());
        assertEquals(7, view.index().knowledgeReady());
        assertEquals(2, view.index().knowledgePending(), "total-ready-failed");
        assertEquals(1, view.index().knowledgeFailed());
        assertEquals(20, view.index().corpusTotal());
        assertEquals(18, view.index().corpusReady());
        assertEquals("2026-10-20T20:00:00", view.index().lastReindexAt());
        assertTrue(view.index().vectorAvailable());
    }

    @Test
    void updateDelegatesToSettingsAndReturnsView() {
        RagSettingsService.Snapshot updated = new RagSettingsService.Snapshot(
                true, 6, 0.3, 3000, 8, "settings");
        when(settings.update(true, 6, 0.3, 3000, 8)).thenReturn(updated);
        when(settings.current()).thenReturn(updated);

        RagAdminService.RagView view = service.update(true, 6, 0.3, 3000, 8);

        assertEquals(6, view.retrieval().topK());
        verify(settings).update(true, 6, 0.3, 3000, 8);
    }

    @Test
    void reindexAllQueuesKnowledgeAndCorpus() {
        when(indexService.reindexKnowledge()).thenReturn(10);
        when(indexService.reindexCorpus()).thenReturn(20);

        RagAdminService.ReindexResult result = service.reindex("all");

        assertEquals(30, result.queued());
        verify(indexService).reindexKnowledge();
        verify(indexService).reindexCorpus();
        verify(settings).markReindexedNow();
    }

    @Test
    void reindexKnowledgeOnly() {
        when(indexService.reindexKnowledge()).thenReturn(10);

        RagAdminService.ReindexResult result = service.reindex("knowledge");

        assertEquals(10, result.queued());
        verify(indexService, never()).reindexCorpus();
    }

    @Test
    void reindexUnknownScopeReturnsMessageWithoutQueueing() {
        RagAdminService.ReindexResult result = service.reindex("bogus");

        assertEquals(0, result.queued());
        assertTrue(result.message().contains("未知 scope"));
        verify(settings, never()).markReindexedNow();
    }

    @Test
    void testDelegatesToEmbeddingClient() {
        RagEmbeddingClient.TestResult testResult = new RagEmbeddingClient.TestResult(
                true, "openai", "OpenAI", "text-embedding-3-small", 1536, 12, "ok");
        when(embeddingClient.test()).thenReturn(testResult);

        assertSame(testResult, service.test());
    }

    @Test
    void vectorUnavailableWhenExtensionMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        assertFalse(service.vectorAvailable());
    }

    @Test
    void vectorAvailableFalseOnJdbcError() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(service.vectorAvailable());
    }
}
