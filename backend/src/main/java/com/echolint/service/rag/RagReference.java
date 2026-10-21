package com.echolint.service.rag;

/**
 * 一条知识库检索结果（RAG Phase B）。
 *
 * @param chunkId  分块 id
 * @param entryId  知识库条目 id
 * @param title    标题（展示/提示词引用）
 * @param category 分类（可能为空）
 * @param content  分块正文
 * @param score    余弦相似度（0~1）
 */
public record RagReference(long chunkId, long entryId, String title, String category,
                           String content, double score) {
}
