-- ============================================================
-- V5：同一录音只允许一个活动流水线任务（防并发双跑）
--
-- 背景（审计 P1-2）：pipeline_tasks 没有唯一约束，历史 DEAD 任务被管理员重试后
-- 可能与「录音重新处理」新建的任务同时处于 PENDING，调度器在同一批里把两条都领取，
-- 同一录音被并发执行两次：AI 费用翻倍，且 status / aiResult / 人工终态互相覆盖。
-- 应用层已在 PipelineTaskService#retry 增加「录音状态白名单 + 活动任务 409 + 录音行锁」，
-- 这里再加数据库兜底：部分唯一索引（PostgreSQL 支持 WHERE 条件）。
--
-- 注意：Flyway 在 Hibernate 建表之前执行（空库场景，见 docs/postgres-migration.md §0.1
-- 与 V3 的已知坑），所以必须用 to_regclass 守卫；全新空库会跳过本迁移，
-- 由应用层的行锁 + 状态校验兜底（此时表由 Hibernate 创建，没有该部分唯一索引）。
-- 迁移后可用以下 SQL 自查（有结果 = 索引存在）：
--   SELECT indexname FROM pg_indexes WHERE tablename = 'pipeline_tasks';
-- ============================================================

DO $$
BEGIN
    IF to_regclass('public.pipeline_tasks') IS NULL THEN
        -- 空库：业务表尚未由 Hibernate 创建，跳过（应用层守卫仍然生效）
        RAISE NOTICE 'pipeline_tasks 不存在，跳过 V5 部分唯一索引创建（空库首次启动属预期）';
        RETURN;
    END IF;

    -- 1) 清理历史重复：同一 recording_id 的 PENDING/RUNNING 任务只保留 id 最大的一条，
    --    其余置为 FAILED 并写 finish 时间（与 PipelineTaskService#markFailedTerminal 语义一致）。
    UPDATE pipeline_tasks t
    SET status = 'FAILED',
        last_error = COALESCE(t.last_error, 'V5 迁移：同一录音存在多个活动任务，重复任务已置为 FAILED'),
        next_attempt_at = NULL,
        finished_at = NOW(),
        updated_at = NOW()
    WHERE t.status IN ('PENDING', 'RUNNING')
      AND EXISTS (
          SELECT 1
          FROM pipeline_tasks newer
          WHERE newer.recording_id = t.recording_id
            AND newer.status IN ('PENDING', 'RUNNING')
            AND newer.id > t.id
      );

    -- 2) 部分唯一索引：一个录音最多一条 PENDING/RUNNING 任务（终态任务不受限制，保留历史）。
    --    用 EXECUTE 执行 DDL，避免不同 PG 版本对 DO 块内 CREATE INDEX 的解析差异。
    EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_pipeline_task_active '
            'ON pipeline_tasks (recording_id) '
            'WHERE status IN (''PENDING'', ''RUNNING'')';
END $$;
