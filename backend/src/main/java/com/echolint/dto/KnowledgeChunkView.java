package com.echolint.dto;

import com.echolint.entity.KnowledgeChunk;

import java.time.LocalDateTime;

/**
 * 知识库分块明细（GET /api/knowledge/{id}/chunks 的 items 元素）。
 *
 * <p>只暴露展示所需字段，不包含 embedding_text（体量大且无展示价值）。</p>
 */
public record KnowledgeChunkView(
        Long chunkId,
        int chunkIndex,
        String content,
        String contentHash,
        String embeddingModel,
        Integer embeddingDim,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static KnowledgeChunkView from(KnowledgeChunk chunk) {
        return new KnowledgeChunkView(
                chunk.getId(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getContentHash(),
                chunk.getEmbeddingModel(),
                chunk.getEmbeddingDim(),
                chunk.getCreatedAt(),
                chunk.getUpdatedAt());
    }
}
