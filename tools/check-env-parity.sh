#!/usr/bin/env bash
# 变量透传一致性守卫（CI / 本地均可用）。
#
# 目的：确保 backend/src/main/resources/application.yml 里出现的每个 ${ENV} 占位符，
# 都能在「docker-compose.yml 的 backend.environment 显式键」或「.env.example 文档键」中
# 找到来源。否则说明该变量在 compose 部署下会被静默忽略（历史上 notify/sampling/
# 集成适配器等 33 个变量就是这样失效的）。
#
# 用法：bash tools/check-env-parity.sh
# 退出码：0 = 一致；1 = 有变量既未在 compose 显式注入、也未在 .env.example 记录。
# 兼容：Bash 3.2（macOS 自带）+ GNU/BSD grep/sed/awk/comm 通用写法，无数组/无 associative array。
set -euo pipefail
export LC_ALL=C

cd "$(dirname "$0")/.."

APP_YML="backend/src/main/resources/application.yml"
COMPOSE_FILE="docker-compose.yml"
ENV_EXAMPLE=".env.example"

for f in "$APP_YML" "$COMPOSE_FILE" "$ENV_EXAMPLE"; do
  if [ ! -f "$f" ]; then
    echo "[FAIL] 缺少文件: $f（请在仓库根执行）" >&2
    exit 1
  fi
done

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/echolint-env-parity.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

# 白名单：确有必要豁免的变量名，空格分隔（当前为空）。
ALLOWLIST=""

# 1) application.yml 中的所有 ${ENV} 占位符（含 ${ENV:default}）
grep -oE '\$\{[A-Z][A-Z0-9_]*' "$APP_YML" | tr -d '${' | sort -u > "$TMP_DIR/app.txt" || true

# 2) docker-compose.yml 中 backend 服务下 environment: 块的显式键
#    （只取 6 空格缩进的 KEY: 行；遇到 4 空格的服务内键即结束 environment 段）
awk '
  /^  [A-Za-z0-9_-]+:/ {
    name = $0; sub(/^  /, "", name); sub(/:.*/, "", name)
    in_backend = (name == "backend")
    in_env = 0
    next
  }
  in_backend && /^    [A-Za-z0-9_-]+:/ {
    key = $0; sub(/^    /, "", key); sub(/:.*/, "", key)
    in_env = (key == "environment")
    next
  }
  in_backend && in_env && /^      [A-Za-z0-9_-]+:/ {
    key = $0; sub(/^ +/, "", key); sub(/:.*/, "", key)
    print key
  }
' "$COMPOSE_FILE" | sort -u > "$TMP_DIR/compose.txt"

# 3) .env.example 中记录过的变量（含注释示例行：# APP_NOTIFY_ENABLED=true）
grep -E '^#?[[:space:]]*[A-Z][A-Z0-9_]*=' "$ENV_EXAMPLE" \
  | sed -E 's/^#?[[:space:]]*//; s/=.*$//' \
  | sort -u > "$TMP_DIR/env-example.txt" || true

if [ ! -s "$TMP_DIR/app.txt" ]; then
  echo "[FAIL] 未能从 $APP_YML 解析到任何 \${ENV} 占位符，检查文件是否被意外改动" >&2
  exit 1
fi
if [ ! -s "$TMP_DIR/compose.txt" ]; then
  echo "[FAIL] 未能从 $COMPOSE_FILE 解析到 backend.environment 键，检查 compose 结构" >&2
  exit 1
fi

# 4) 已覆盖集合 = compose 显式键 ∪ .env.example 文档键
cat "$TMP_DIR/compose.txt" "$TMP_DIR/env-example.txt" | sort -u > "$TMP_DIR/covered.txt"

# 5) 未覆盖 = application.yml 有、但两处都没有
comm -23 "$TMP_DIR/app.txt" "$TMP_DIR/covered.txt" > "$TMP_DIR/uncovered.txt"

: > "$TMP_DIR/missing.txt"
while IFS= read -r var; do
  [ -n "$var" ] || continue
  # Bash 3.2 兼容的白名单过滤（空格包围匹配）
  case " $ALLOWLIST " in
    *" $var "*) continue ;;
  esac
  printf '%s\n' "$var" >> "$TMP_DIR/missing.txt"
done < "$TMP_DIR/uncovered.txt"

if [ -s "$TMP_DIR/missing.txt" ]; then
  echo "[FAIL] 以下变量在 application.yml 中被引用，但既未在 docker-compose.yml 的" >&2
  echo "       backend.environment 显式注入，也未在 .env.example 中记录（会静默失效）：" >&2
  sed 's/^/  - /' "$TMP_DIR/missing.txt" >&2
  echo "" >&2
  echo "修复：为 backend 增加 environment 条目（\${VAR:-默认值}），或在 .env.example 补充注释示例。" >&2
  exit 1
fi

app_count="$(wc -l < "$TMP_DIR/app.txt" | tr -d ' ')"
compose_count="$(wc -l < "$TMP_DIR/compose.txt" | tr -d ' ')"
env_count="$(wc -l < "$TMP_DIR/env-example.txt" | tr -d ' ')"
echo "[OK] 变量透传一致：application.yml ${app_count} 个变量全部有来源（compose 显式 ${compose_count} 个 ∪ .env.example ${env_count} 个）"
