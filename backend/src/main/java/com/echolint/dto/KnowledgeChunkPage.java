package com.echolint.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库索引明细（GET /api/knowledge/{id}/chunks，仅 ADMIN）。
 *
 * <p>头部字段来自知识库条目自身的索引状态；embeddingModel / embeddingDimensions 取自
 * 条目的第一个分块（无分块时为 null），便于前端说明「这份索引是用哪个模型建的」。</p>
 */
public record KnowledgeChunkPage(
        Long entryId,
        String title,
        String indexStatus,
        String indexError,
        LocalDateTime indexedAt,
        String embeddingHash,
        String embeddingModel,
        Integer embeddingDimensions,
        long total,
        int page,
        int size,
        List<KnowledgeChunkView> items) {
}
