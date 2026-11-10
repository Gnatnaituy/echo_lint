-- ============================================================
-- V7：用户级菜单绑定（在角色允许范围内收窄可见菜单）
--
-- 背景：ADMIN 需要在用户管理里为每个用户勾选可见菜单；登录态（/api/auth/me、login）
-- 返回生效菜单，前端据此隐藏入口。菜单绑定不改变 SecurityConfig 的 URL 权限矩阵。
--
-- 存储：逗号分隔的 MenuKey 枚举名；NULL / 空串 = 不限制（= 角色允许的全部菜单）。
--
-- 与 V5/V6 相同的守卫写法：Flyway 在 Hibernate 建表之前执行，全新空库此时 app_user
-- 尚未创建 —— to_regclass 为 NULL 时跳过本迁移，由实体字段（ddl-auto=update）建列。
-- 历史库执行后可用以下 SQL 自查（有结果 = 列存在）：
--   SELECT column_name FROM information_schema.columns
--   WHERE table_name = 'app_user' AND column_name = 'menu_keys';
-- ============================================================

DO $$
BEGIN
    IF to_regclass('public.app_user') IS NULL THEN
        RAISE NOTICE 'app_user 不存在，跳过 V7 菜单绑定列创建（空库首次启动属预期，由 Hibernate 建列）';
        RETURN;
    END IF;

    ALTER TABLE public.app_user ADD COLUMN IF NOT EXISTS menu_keys varchar(512);
END $$;
