-- ============================================================
-- V4：列表/报表检索索引（审计 BC-04 / F-10）
--
-- 背景：
--   * RecordingSpecifications 的关键字检索是 lower(transcript) / lower(file_name) 的
--     %kw% 前后模糊匹配，等值过滤是 lower(agent_name/team_name/assigned_to)，
--     时间排序/过滤用 COALESCE(call_time, upload_time)——列被表达式包裹后普通 btree 索引不可用，
--     需要按同样的表达式建索引（pg_trgm 支撑 %kw% LIKE）；
--   * corpus_entries 此前只有 label 索引，RagRetrievalService.pickByLabel 的
--     向量排序每次全表扫描 + 逐行 text::vector 转换。
--
-- 空库注意（与 V2 相同的守卫写法）：Flyway 先于 Hibernate ddl-auto 执行，
-- 全新空库执行本迁移时业务表尚未创建，因此所有 DDL 都包在 to_regclass 守卫里：
--   * 空库：两个 DO 块整体跳过，不会因「表不存在」导致启动失败（V3 的已知坑）；
--   * 已存在的旧库：recordings / corpus_entries 已由 Hibernate 建好，正常创建索引。
--
-- 注意：HNSW 索引维度写死 1024，必须与 app.rag.embedding.dimensions（V3 迁移）一致；
--       更换 embedding 维度时需要重建该索引（DROP 后由本迁移重新创建）。
-- ============================================================

-- 关键词 %kw% 模糊匹配需要 trigram 支持；扩展已存在时为空操作。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- recordings：列表/报表的过滤与排序表达式索引
DO $$
BEGIN
    IF to_regclass('public.recordings') IS NOT NULL THEN
        -- 关键字：lower(transcript) LIKE '%kw%' / lower(file_name) LIKE '%kw%'
        CREATE INDEX IF NOT EXISTS idx_recordings_transcript_trgm
            ON public.recordings USING gin (lower(transcript) gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_recordings_file_name_trgm
            ON public.recordings USING gin (lower(file_name) gin_trgm_ops);

        -- 精确过滤：assigned_to / agent_name / team_name 均以 lower() 比较
        CREATE INDEX IF NOT EXISTS idx_recordings_agent_name_lower
            ON public.recordings (lower(agent_name));
        CREATE INDEX IF NOT EXISTS idx_recordings_team_name_lower
            ON public.recordings (lower(team_name));
        CREATE INDEX IF NOT EXISTS idx_recordings_assigned_to_lower
            ON public.recordings (lower(assigned_to));

        -- 时间排序/过滤：call_time 为空时回退 upload_time
        CREATE INDEX IF NOT EXISTS idx_recordings_effective_call_time
            ON public.recordings (COALESCE(call_time, upload_time));
    END IF;
END $$;

-- corpus_entries：语义 few-shot 的 HNSW 索引（部分索引，未向量化的行不占索引）
DO $$
BEGIN
    IF to_regclass('public.corpus_entries') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_corpus_entries_hnsw
            ON public.corpus_entries USING hnsw ((embedding_text::vector(1024)) vector_cosine_ops)
            WHERE embedding_text IS NOT NULL;
    END IF;
END $$;
