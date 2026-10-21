-- ============================================================
-- V3：RAG embedding 维度从 1536 调整为 1024（阿里云百炼）
--
-- 背景：embedding 改用国内阿里云百炼
-- qwen3.7-text-embedding（默认 1024 维，支持 2560/2048/1536/1024/...
-- 通过 dimensions 参数指定）。HNSW 索引维度必须与
-- app.rag.embedding.dimensions 一致，因此重建表达式索引。
--
-- 升级保护：如果历史库里已有其它维度的向量，先清空分块/语料向量，
-- 由应用启动回填或管理员「全量重建」按 1024 维重新向量化。
-- ============================================================

-- 清理非 1024 维的历史向量（RAG 未启用过的库为空操作）
DELETE FROM knowledge_chunks
WHERE embedding_dim IS NOT NULL AND embedding_dim <> 1024;

-- 旧向量已清理；把条目/语料状态复位，等待重新索引
UPDATE knowledge_entries
SET index_status = 'PENDING',
    index_error = NULL,
    indexed_at = NULL,
    embedding_hash = NULL,
    chunk_count = 0
WHERE embedding_hash IS NOT NULL;

UPDATE corpus_entries
SET embedding_text = NULL,
    embedding_model = NULL,
    embedding_hash = NULL,
    embedding_updated_at = NULL
WHERE embedding_text IS NOT NULL;

-- 重建 HNSW 表达式索引：vector(1024)
DROP INDEX IF EXISTS idx_knowledge_chunks_hnsw;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_hnsw
    ON knowledge_chunks USING hnsw ((embedding_text::vector(1024)) vector_cosine_ops);
