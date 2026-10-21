package com.echolint.domain;

/**
 * 知识库条目的 RAG 索引状态（Batch 6）。
 *
 * <p>PENDING：等待解析/向量化；PARSING：文档抽取中；INDEXING：分块向量化中；
 * READY：索引可用；FAILED：失败（错误信息见 last indexError，可手动重建）。</p>
 */
public enum RagIndexStatus {
    PENDING,
    PARSING,
    INDEXING,
    READY,
    FAILED
}
