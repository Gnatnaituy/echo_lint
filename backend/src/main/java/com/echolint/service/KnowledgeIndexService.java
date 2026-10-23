package com.echolint.service;

import com.echolint.dto.KnowledgeChunkPage;
import com.echolint.dto.KnowledgeChunkView;
import com.echolint.entity.KnowledgeChunk;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeChunkRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库索引明细（Batch 6 管理端）：条目索引状态 + 分块明细分页。
 *
 * <p>只读接口，仅 ADMIN 可访问（URL 规则见 SecurityConfig）；embedding 模型/维度取自条目的
 * 第一个分块，前端据此展示索引指纹与向量规格。分块按 chunkIndex 升序分页。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIndexService {

    /** 分块按条目内序号升序展示（0 基） */
    private static final Sort CHUNK_SORT = Sort.by(Sort.Direction.ASC, "chunkIndex");

    private final KnowledgeEntryRepository entryRepository;
    private final KnowledgeChunkRepository chunkRepository;

    /**
     * 查询条目及其分块明细。page / size 由调用方（控制器）夹取到合法区间。
     *
     * @throws BizException 条目不存在时 404
     */
    public KnowledgeChunkPage detail(Long entryId, int page, int size) {
        KnowledgeEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new BizException("知识库条目不存在: " + entryId, HttpStatus.NOT_FOUND));

        Page<KnowledgeChunk> chunks = chunkRepository.findByEntryId(entryId,
                PageRequest.of(page, size, CHUNK_SORT));
        // 首块的向量元数据代表该条目当前索引用到的 embedding 模型/维度；无分块时为 null
        KnowledgeChunk first = chunkRepository.findFirstByEntryIdOrderByChunkIndexAsc(entryId)
                .orElse(null);
        List<KnowledgeChunkView> items = chunks.getContent().stream()
                .map(KnowledgeChunkView::from)
                .toList();

        return new KnowledgeChunkPage(
                entry.getId(),
                entry.getTitle(),
                entry.getIndexStatus(),
                entry.getIndexError(),
                entry.getIndexedAt(),
                entry.getEmbeddingHash(),
                first == null ? null : first.getEmbeddingModel(),
                first == null ? null : first.getEmbeddingDim(),
                chunks.getTotalElements(),
                chunks.getNumber(),
                chunks.getSize(),
                items);
    }
}
