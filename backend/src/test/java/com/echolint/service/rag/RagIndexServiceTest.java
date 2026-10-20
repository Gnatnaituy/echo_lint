package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.RagIndexStatus;
import com.echolint.entity.CorpusEntry;
import com.echolint.entity.KnowledgeChunk;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.KnowledgeChunkRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.service.AiInvocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 索引服务：知识库分块+向量化、内容指纹跳过、失败标记、语料向量化与重建入口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagIndexServiceTest {

    @Mock private KnowledgeEntryRepository knowledgeEntries;
    @Mock private KnowledgeChunkRepository chunks;
    @Mock private CorpusEntryRepository corpusEntries;
    @Mock private RagEmbeddingClient embeddingClient;
    @Mock private RagSettingsService settings;
    @Mock private ThreadPoolTaskExecutor executor;

    private RagProperties properties;
    private RagIndexService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getChunk().setChars(20);
        properties.getChunk().setOverlap(4);
        properties.getIndex().setStartupBackfill(true);

        when(settings.isEnabled()).thenReturn(true);
        when(settings.current()).thenReturn(
                new RagSettingsService.Snapshot(true, 4, 0.25, 2400, 5, "settings"));
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any());
        when(knowledgeEntries.save(any(KnowledgeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(corpusEntries.save(any(CorpusEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new RagIndexService(knowledgeEntries, chunks, corpusEntries, embeddingClient,
                settings, properties, executor);
    }

    private KnowledgeEntry entry(String content) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(1L);
        entry.setTitle("SOP 话术");
        entry.setCategory("话术规范");
        entry.setContent(content);
        entry.setIndexStatus(RagIndexStatus.PENDING.name());
        return entry;
    }

    @Test
    void indexesKnowledgeChunksAndMarksReady() {
        String content = "第一段内容比较长需要被切成多个块。".repeat(6);
        KnowledgeEntry entry = entry(content);
        when(knowledgeEntries.findById(1L)).thenReturn(Optional.of(entry));
        int chunkCount = TextChunker.split(content, properties.effectiveChunkChars(),
                properties.effectiveChunkOverlap()).size();
        when(embeddingClient.embed(anyList(), isNull(), eq(AiInvocationService.STAGE_EMBED_INDEX)))
                .thenReturn(vectors(chunkCount));

        service.indexKnowledgeNow(1L);

        verify(chunks).deleteByEntryId(1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunks).saveAll(captor.capture());
        assertEquals(chunkCount, captor.getValue().size());
        assertEquals(chunkCount, entry.getChunkCount());
        assertEquals(RagIndexStatus.READY.name(), entry.getIndexStatus());
        assertNotNull(entry.getIndexedAt());
        assertNotNull(entry.getEmbeddingHash());
        assertNull(entry.getIndexError());
        KnowledgeChunk first = captor.getValue().get(0);
        assertEquals(1L, first.getEntryId());
        assertTrue(first.getEmbeddingText().startsWith("["));
        assertEquals(properties.effectiveEmbeddingModel(), first.getEmbeddingModel());
        assertEquals(properties.effectiveDimensions(), first.getEmbeddingDim());
    }

    @Test
    void skipsWhenContentFingerprintUnchanged() {
        String content = "稳定的 SOP 内容";
        KnowledgeEntry entry = entry(content);
        entry.setIndexStatus(RagIndexStatus.READY.name());
        entry.setChunkCount(2);
        entry.setEmbeddingHash(RagIndexService.sha256("SOP 话术\n话术规范\n" + content));
        when(knowledgeEntries.findById(1L)).thenReturn(Optional.of(entry));

        service.indexKnowledgeNow(1L);

        verify(embeddingClient, never()).embed(anyList(), any(), any());
        verify(chunks, never()).deleteByEntryId(any());
    }

    @Test
    void marksFailedWhenEmbeddingFails() {
        KnowledgeEntry entry = entry("内容");
        when(knowledgeEntries.findById(1L)).thenReturn(Optional.of(entry));
        when(embeddingClient.embed(anyList(), isNull(), eq(AiInvocationService.STAGE_EMBED_INDEX)))
                .thenThrow(new RagEmbeddingException("embedding down"));

        service.indexKnowledgeNow(1L);

        assertEquals(RagIndexStatus.FAILED.name(), entry.getIndexStatus());
        assertTrue(entry.getIndexError().contains("embedding down"));
    }

    @Test
    void emptyContentMarksReadyWithZeroChunks() {
        KnowledgeEntry entry = entry("");
        when(knowledgeEntries.findById(1L)).thenReturn(Optional.of(entry));

        service.indexKnowledgeNow(1L);

        assertEquals(RagIndexStatus.READY.name(), entry.getIndexStatus());
        assertEquals(0, entry.getChunkCount());
        verify(chunks).deleteByEntryId(1L);
        verify(embeddingClient, never()).embed(anyList(), any(), any());
    }

    @Test
    void indexesCorpusEntry() {
        CorpusEntry corpus = new CorpusEntry();
        corpus.setId(7L);
        corpus.setLabel(CorpusLabel.VIOLATION);
        corpus.setTranscript("坐席辱骂客户");
        when(corpusEntries.findById(7L)).thenReturn(Optional.of(corpus));
        when(embeddingClient.embed(anyList(), isNull(), eq(AiInvocationService.STAGE_EMBED_INDEX)))
                .thenReturn(vectors(1));

        service.indexCorpusNow(7L);

        assertNotNull(corpus.getEmbeddingText());
        assertEquals(properties.effectiveEmbeddingModel(), corpus.getEmbeddingModel());
        assertNotNull(corpus.getEmbeddingHash());
        assertNotNull(corpus.getEmbeddingUpdatedAt());
    }

    @Test
    void reindexCountsAndEnqueuesAsynchronously() {
        when(knowledgeEntries.findAll()).thenReturn(List.of(entry("a"), entry("b")));
        when(corpusEntries.findAll()).thenReturn(List.of(new CorpusEntry()));

        assertEquals(2, service.reindexKnowledge());
        assertEquals(1, service.reindexCorpus());

        verify(executor, atLeast(3)).execute(any());
    }

    @Test
    void toPgVectorUsesTextFormat() {
        assertEquals("[1.0,2.5]", RagIndexService.toPgVector(new float[]{1f, 2.5f}));
    }

    @Test
    void sha256IsStableHex() {
        String value = RagIndexService.sha256("abc");
        assertEquals(64, value.length());
        assertEquals(RagIndexService.sha256("abc"), value);
        assertNotEquals(RagIndexService.sha256("abd"), value);
    }

    private static List<float[]> vectors(int count) {
        List<float[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new float[]{0.1f, 0.2f, 0.3f});
        }
        return out;
    }
}
