#!/usr/bin/env bash
# =============================================================================
# EchoLint 生产库初始化（P2-28）：为低权限应用角色 echolint_app 设置口令。
#
# 执行时机：仅当 pg-data 命名卷为空、postgres 容器「首次」初始化数据库时，
#   由官方 docker-entrypoint 在 01-app-role.sql 之后执行（文件名排序）。
#   已有数据卷不会执行，迁移步骤见 deploy/DEPLOY.md §4。
#
# 安全：口令只来自容器环境变量 ECHOLINT_APP_PASSWORD（由 deploy/.env.prod 注入）；
#   若未设置或仍为示例占位值，脚本以非零退出中止容器启动（fail-closed），
#   避免生产环境以空口令/示例口令上线。
#
# 手工执行（已有数据卷迁移时）：
#   docker exec -e ECHOLINT_APP_PASSWORD='强口令' -i echolint-postgres \
#     bash /docker-entrypoint-initdb.d/02-app-password.sh
# =============================================================================
set -euo pipefail

: "${POSTGRES_USER:?缺少 POSTGRES_USER}"
: "${POSTGRES_DB:?缺少 POSTGRES_DB}"
: "${ECHOLINT_APP_PASSWORD:?ECHOLINT_APP_PASSWORD 未设置：请在 deploy/.env.prod 配置后重试}"

case "$ECHOLINT_APP_PASSWORD" in
  "" | *CHANGE_ME*)
    echo "[init] 拒绝空口令或示例口令（含 CHANGE_ME），请先在 deploy/.env.prod 设置 ECHOLINT_APP_PASSWORD" >&2
    exit 1
    ;;
esac

# 走容器内本地 socket（trust 认证），无需再传超级用户口令；-v 变量由 psql 安全转义
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_pw="$ECHOLINT_APP_PASSWORD" <<'SQL'
ALTER ROLE echolint_app PASSWORD :'app_pw';
SQL

echo "[init] echolint_app 口令已设置（低权限应用角色就绪）"
