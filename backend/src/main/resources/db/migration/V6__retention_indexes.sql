-- ============================================================
-- V6：数据保留清理（审计 P2-30）所需的 created_at 索引
--
-- 背景：DataRetentionService 每日按 created_at 分批删除超期行：
--   ai_invocations（默认 180 天，含请求/回复 TEXT，容量大头）
--   audit_logs（默认 365 天）
--   pipeline_logs（默认 90 天）
-- 删除前需要 `WHERE created_at < ? ORDER BY id LIMIT 500`，没有索引会全表扫描。
--
-- 与 V4/V5 相同的守卫写法：Flyway 先于 Hibernate ddl-auto 执行，全新空库此时业务表
-- 尚未创建 —— to_regclass 为 NULL 时整体跳过，不因「表不存在」导致启动失败。
-- 新库首启后 Hibernate 会按实体 @Index 建同名索引，因此这里主要覆盖历史库。
--
-- 注意：索引名与实体声明保持一致（idx_ai_inv_created / idx_audit_created / idx_log_created），
--       CREATE INDEX IF NOT EXISTS 在已有同名索引时为空操作，可重复执行。
-- ============================================================

DO $$
BEGIN
    IF to_regclass('public.ai_invocations') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_ai_inv_created
            ON public.ai_invocations (created_at);
    END IF;

    IF to_regclass('public.audit_logs') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_audit_created
            ON public.audit_logs (created_at);
    END IF;

    IF to_regclass('public.pipeline_logs') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_log_created
            ON public.pipeline_logs (created_at);
    END IF;
END $$;
