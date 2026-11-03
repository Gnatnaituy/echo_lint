-- =============================================================================
-- EchoLint 生产库初始化（P2-28：低权限应用角色）
--
-- 执行时机：仅当 pg-data 命名卷为空、postgres 容器「首次」初始化数据库时，
--   由官方 docker-entrypoint 以 bootstrap 超级用户（POSTGRES_USER=audit）执行。
--   已有数据卷不会再次执行本文件，迁移步骤见 deploy/DEPLOY.md §4。
--
-- 作用：
--   1) 创建 pgvector 扩展（非 trusted extension，必须由超级用户创建；
--      Flyway V2 的 CREATE EXTENSION IF NOT EXISTS 由应用角色执行时是幂等 no-op）；
--   2) 创建低权限应用角色 echolint_app（非超级用户、无建库/建角色权限），
--      并设为 echolint 库 owner（PG15+ 库 owner 自动拥有 public schema 及 DDL 权限，
--      满足 Flyway 迁移与 Hibernate ddl-auto: update 的建表/改表需求）；
--   3) 应用角色口令由下一个 init 脚本 02-app-password.sh 从 ECHOLINT_APP_PASSWORD 注入。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 幂等：已有角色时跳过（方便在「已有数据卷」上手工重跑本文件）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'echolint_app') THEN
    CREATE ROLE echolint_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

-- :"DBNAME" 是 psql 预定义变量，指向当前连接的数据库（生产固定为 echolint）
ALTER DATABASE :"DBNAME" OWNER TO echolint_app;

GRANT CREATE, USAGE ON SCHEMA public TO echolint_app;
