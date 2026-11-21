-- ============================================================
-- V8：pipeline_tasks 增加 traceparent 列（OTel trace 续接）
--
-- 背景：上传请求与约 5 秒后的调度执行是两个独立瞬间，ThreadLocal 的 trace 栈无法跨过
-- 该间隔。入队时把请求的 W3C traceparent（00-traceId-spanId-flags，定长 55）落库，
-- 执行时恢复为 remote parent，让「上传 → 转写 → DFA → AI 复筛」在 Jaeger 里连成一条 trace。
-- 可空列：tracing 未启用 / 历史数据 / 异步入队无 span 时为 NULL，执行时按无续接处理。
--
-- 与 V5/V7 相同的守卫写法：Flyway 在 Hibernate 建表之前执行，全新空库此时 pipeline_tasks
-- 尚未创建——to_regclass 为 NULL 时跳过本迁移，由实体字段（ddl-auto=update）建列。
-- 历史库执行后可用以下 SQL 自查（有结果 = 列存在）：
--   SELECT column_name FROM information_schema.columns
--   WHERE table_name = 'pipeline_tasks' AND column_name = 'traceparent';
-- ============================================================

DO $$
BEGIN
    IF to_regclass('public.pipeline_tasks') IS NULL THEN
        RAISE NOTICE 'pipeline_tasks 不存在，跳过 V8 traceparent 列创建（空库首次启动属预期，由 Hibernate 建列）';
        RETURN;
    END IF;

    ALTER TABLE public.pipeline_tasks ADD COLUMN IF NOT EXISTS traceparent varchar(55);
END $$;
