package com.echolint.service;

import com.echolint.dto.KnowledgeChunkPage;
import com.echolint.dto.KnowledgeChunkView;
import com.echolint.entity.KnowledgeChunk;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeChunkRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 索引明细服务：条目 + 分块映射、首块 embedding 模型/维度、空分块与 404。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIndexServiceTest {

    private static final LocalDateTime INDEXED_AT = LocalDateTime.of(2026, 10, 23, 12, 0);
    private static final LocalDateTime CHUNK_TIME = LocalDateTime.of(2026, 10, 23, 11, 30);

    @Mock
    private KnowledgeEntryRepository entryRepository;
    @Mock
    private KnowledgeChunkRepository chunkRepository;

    private KnowledgeIndexService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeIndexService(entryRepository, chunkRepository);
    }

    private KnowledgeEntry entry() {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(12L);
        entry.setTitle("话术规范");
        entry.setIndexStatus("READY");
        entry.setIndexError(null);
        entry.setIndexedAt(INDEXED_AT);
        entry.setEmbeddingHash("entry-hash");
        return entry;
    }

    private KnowledgeChunk chunk(long id, int index, String model, Integer dim) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setEntryId(12L);
        chunk.setChunkIndex(index);
        chunk.setContent("第 " + index + " 段正文");
        chunk.setContentHash("chunk-hash-" + index);
        chunk.setEmbeddingModel(model);
        chunk.setEmbeddingDim(dim);
        chunk.setCreatedAt(CHUNK_TIME);
        chunk.setUpdatedAt(CHUNK_TIME);
        return chunk;
    }

    @Test
    void mapsEntryAndUsesFirstChunkEmbeddingMetadata() {
        when(entryRepository.findById(12L)).thenReturn(Optional.of(entry()));
        // 当前页（第 2 页）里的分块模型与首块不同，用来证明头部向量信息取自首块查询
        KnowledgeChunk pageChunk = chunk(31L, 2, "other-model", 768);
        Page<KnowledgeChunk> page = new PageImpl<>(List.of(pageChunk), PageRequest.of(1, 2), 5);
        when(chunkRepository.findByEntryId(eq(12L), any(Pageable.class))).thenReturn(page);
        when(chunkRepository.findFirstByEntryIdOrderByChunkIndexAsc(12L))
                .thenReturn(Optional.of(chunk(1L, 0, "qwen3.7-text-embedding", 1024)));

        KnowledgeChunkPage result = service.detail(12L, 1, 2);

        assertEquals(12L, result.entryId());
        assertEquals("话术规范", result.title());
        assertEquals("READY", result.indexStatus());
        assertNull(result.indexError());
        assertEquals(INDEXED_AT, result.indexedAt());
        assertEquals("entry-hash", result.embeddingHash());
        assertEquals("qwen3.7-text-embedding", result.embeddingModel(), "取自条目首块");
        assertEquals(1024, result.embeddingDimensions());
        assertEquals(5L, result.total());
        assertEquals(1, result.page());
        assertEquals(2, result.size());
        assertEquals(1, result.items().size());

        KnowledgeChunkView item = result.items().get(0);
        assertEquals(31L, item.chunkId());
        assertEquals(2, item.chunkIndex());
        assertEquals("第 2 段正文", item.content());
        assertEquals("chunk-hash-2", item.contentHash());
        assertEquals("other-model", item.embeddingModel());
        assertEquals(768, item.embeddingDim());
        assertEquals(CHUNK_TIME, item.createdAt());
        assertEquals(CHUNK_TIME, item.updatedAt());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(chunkRepository).findByEntryId(eq(12L), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(2, captor.getValue().getPageSize());
        assertEquals("chunkIndex: ASC", captor.getValue().getSort().toString());
    }

    @Test
    void emptyChunksYieldsZeroTotalAndNullEmbeddingMetadata() {
        when(entryRepository.findById(12L)).thenReturn(Optional.of(entry()));
        PageRequest pageable = PageRequest.of(0, 10);
        when(chunkRepository.findByEntryId(eq(12L), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));
        when(chunkRepository.findFirstByEntryIdOrderByChunkIndexAsc(12L)).thenReturn(Optional.empty());

        KnowledgeChunkPage result = service.detail(12L, 0, 10);

        assertEquals(0L, result.total());
        assertEquals(0, result.page());
        assertEquals(10, result.size());
        assertTrue(result.items().isEmpty());
        assertNull(result.embeddingModel(), "无分块时模型未知");
        assertNull(result.embeddingDimensions(), "无分块时维度未知");
        // 条目本身的信息仍然返回，便于前端展示「尚未索引/正文为空」
        assertEquals("READY", result.indexStatus());
        assertEquals("话术规范", result.title());
    }

    @Test
    void unknownEntryIs404() {
        when(entryRepository.findById(404L)).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.detail(404L, 0, 10));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertTrue(error.getMessage().contains("404"));
        verifyNoInteractions(chunkRepository);
    }
}
