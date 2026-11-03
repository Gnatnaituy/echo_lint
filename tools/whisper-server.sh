#!/usr/bin/env bash
# 启动本机 whisper.cpp 转写服务，并把端点伪装成兼容的
# POST /v1/audio/transcriptions，从而让 EchoLint 后端零代码改道到本地。
#
# 依赖：
#   macOS:  brew install whisper.cpp ffmpeg
#   Linux:  apt install ffmpeg + 编译安装 whisper.cpp（或发行版提供 whisper-server 时直接用）
#   模型：~/models/whisper/ggml-large-v3-turbo.bin（可用 WHISPER_MODEL 覆盖）
#
# 用法：
#   tools/whisper-server.sh              # 前台启动（Ctrl-C 停止）
#   tools/whisper-server.sh --daemon     # 后台启动：写 PID 文件 + 就绪回轮询（默认 30s），日志持久保存
#   tools/whisper-server.sh --status     # 查看后台状态（运行中 exit 0，未运行 exit 1）
#   tools/whisper-server.sh --stop       # 停止后台进程（SIGTERM，10s 未退出再 SIGKILL）
#   tools/whisper-server.sh --help       # 显示帮助
#
# 日志与 PID（均可覆盖）：
#   WHISPER_STATE_DIR      默认 ${XDG_STATE_HOME:-$HOME/.local/state}/echolint
#   WHISPER_LOG            默认 $WHISPER_STATE_DIR/whisper-server.log
#   WHISPER_PID_FILE       默认 $WHISPER_STATE_DIR/whisper-server.pid
#   WHISPER_READY_TIMEOUT  后台启动就绪轮询超时秒数（默认 30）
#
# 启动后把下面两行写进 .env，再 `docker compose up -d` 即可：
#   WHISPER_BASE_URL=http://host.docker.internal:9900
#   WHISPER_API_KEY=local        # whisper.cpp 本身不校验，但后端要求非空
#
# Linux 生产机（Docker Engine）：
#   容器内 host.docker.internal 由 docker-compose.yml 的 extra_hosts: host-gateway 提供；
#   本脚本默认只监听 127.0.0.1，容器访问不到，需显式绑定所有网卡：
#     WHISPER_HOST=0.0.0.0 tools/whisper-server.sh --daemon
#   whisper.cpp 无鉴权，务必用防火墙只放行 Docker 网桥网段（默认 172.17.0.0/16）：
#     sudo ufw allow from 172.16.0.0/12 to any port 9900 proto tcp
#   排障（backend 镜像无 curl，用 bash 探 TCP；HTTP 层在宿主机验证）：
#     docker exec echolint-backend bash -c 'echo > /dev/tcp/host.docker.internal/9900 && echo REACHABLE'
#     curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9900/v1/audio/transcriptions \
#       -F file=@sample.wav -F model=whisper-1
set -euo pipefail

MODEL="${WHISPER_MODEL:-$HOME/models/whisper/ggml-large-v3-turbo.bin}"
HOST="${WHISPER_HOST:-127.0.0.1}"
PORT="${WHISPER_PORT:-9900}"
THREADS="${WHISPER_THREADS:-8}"          # M1 Pro: 8 个性能核
LANG="${WHISPER_LANG:-auto}"             # auto = 中英混合自动识别
TMP_DIR="${WHISPER_TMP_DIR:-/tmp/whisper-tmp}"

STATE_DIR="${WHISPER_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/echolint}"
LOG="${WHISPER_LOG:-$STATE_DIR/whisper-server.log}"
PID_FILE="${WHISPER_PID_FILE:-$STATE_DIR/whisper-server.pid}"
READY_TIMEOUT="${WHISPER_READY_TIMEOUT:-30}"

# 关键：把默认的 /inference 改成兼容的 /v1/audio/transcriptions 路径，后端无需任何改动
INFERENCE_PATH="/v1/audio/transcriptions"

# 绑定 0.0.0.0 时，本机就绪探测要用 127.0.0.1
PROBE_HOST="$HOST"
[ "$HOST" = "0.0.0.0" ] && PROBE_HOST="127.0.0.1"

SERVER_ARGS=(
  -m "$MODEL"
  --host "$HOST" --port "$PORT"
  --inference-path "$INFERENCE_PATH"
  -t "$THREADS"
  -l "$LANG"
  --convert --tmp-dir "$TMP_DIR"
)

die() { echo "错误: $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: tools/whisper-server.sh [--daemon | --status | --stop | --help]

  (无参数)   前台启动 whisper.cpp 转写服务（Ctrl-C 停止）
  --daemon   后台启动：写 PID 文件，就绪后返回；日志默认 ~/.local/state/echolint/whisper-server.log
  --status   查看后台进程状态（运行中 exit 0，未运行 exit 1）
  --stop     停止后台进程（SIGTERM，10s 未退出再 SIGKILL）
  --help     显示本帮助

主要环境变量：
  WHISPER_MODEL(模型路径)  WHISPER_HOST(127.0.0.1)  WHISPER_PORT(9900)
  WHISPER_THREADS(8)       WHISPER_LANG(auto)      WHISPER_TMP_DIR(/tmp/whisper-tmp)
  WHISPER_STATE_DIR / WHISPER_LOG / WHISPER_PID_FILE / WHISPER_READY_TIMEOUT(30)

Linux 生产机：容器访问需 WHISPER_HOST=0.0.0.0，并用防火墙只放行 Docker 网桥网段。
EOF
}

require_runtime() {
  command -v whisper-server >/dev/null 2>&1 \
    || die "未找到 whisper-server，请先安装 whisper.cpp（macOS: brew install whisper.cpp；Linux: 编译安装）"
  [ -f "$MODEL" ] || die "模型不存在: $MODEL
下载命令:
  mkdir -p \"\$(dirname '$MODEL')\" && curl -L -o '$MODEL' \\
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin"
  # --convert 需要 ffmpeg 来解码 mp3/m4a 等非 16k WAV 格式
  command -v ffmpeg >/dev/null 2>&1 \
    || die "未找到 ffmpeg（--convert 依赖它解码 mp3/m4a），请先安装"
}

pid_value() {
  [ -f "$PID_FILE" ] && cat "$PID_FILE" 2>/dev/null || true
}

is_running() {
  _pid="$(pid_value)"
  [ -n "$_pid" ] || return 1
  case "$_pid" in *[!0-9]*) return 1 ;; esac
  kill -0 "$_pid" 2>/dev/null
}

port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep -q ":$PORT "
  else
    return 1
  fi
}

http_ready() {
  command -v curl >/dev/null 2>&1 || return 1
  _code="$(curl -s -o /dev/null -m 2 -w '%{http_code}' "http://$PROBE_HOST:$PORT/" 2>/dev/null || true)"
  [ -n "$_code" ] && [ "$_code" != "000" ]
}

wait_ready() {
  _i=0
  while [ "$_i" -lt "$READY_TIMEOUT" ]; do
    if ! is_running; then
      echo "错误: whisper-server 启动后立即退出，日志末尾：" >&2
      [ -f "$LOG" ] && tail -n 20 "$LOG" >&2 || true
      return 1
    fi
    if command -v curl >/dev/null 2>&1; then
      http_ready && return 0
    else
      # 没有 curl 时退化为「进程存活 + 短暂等待」粗判（仍建议安装 curl）
      sleep 2
      is_running && return 0
    fi
    sleep 1
    _i=$((_i + 1))
  done
  echo "错误: ${READY_TIMEOUT}s 内未就绪，请查看日志：$LOG" >&2
  return 1
}

stop_pid() {
  _target="$1"
  [ -n "$_target" ] || return 0
  kill "$_target" 2>/dev/null || true
  _i=0
  while kill -0 "$_target" 2>/dev/null; do
    _i=$((_i + 1))
    if [ "$_i" -ge 10 ]; then
      kill -9 "$_target" 2>/dev/null || true
      break
    fi
    sleep 1
  done
}

start_daemon() {
  require_runtime
  if is_running; then
    die "后台服务已在运行 (pid $(pid_value))；如需重启请先执行 --stop"
  fi
  rm -f "$PID_FILE"   # 清理上次异常退出的残留
  if port_in_use; then
    die "端口 $PORT 已被占用（可能是未受本脚本管理的 whisper-server）"
  fi
  mkdir -p "$STATE_DIR" "$TMP_DIR"
  printf '=== %s 启动 whisper-server（host=%s port=%s）===\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" "$HOST" "$PORT" >>"$LOG"
  nohup whisper-server "${SERVER_ARGS[@]}" >>"$LOG" 2>&1 &
  _pid=$!
  printf '%s\n' "$_pid" >"$PID_FILE"
  echo "已后台启动 (pid $_pid)，日志: $LOG"
  if ! wait_ready; then
    stop_pid "$_pid"
    rm -f "$PID_FILE"
    return 1
  fi
  echo "就绪: http://$PROBE_HOST:$PORT$INFERENCE_PATH"
}

stop_daemon() {
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "未运行（无存活进程）"
    return 0
  fi
  _pid="$(pid_value)"
  echo "正在停止 pid=$_pid ..."
  stop_pid "$_pid"
  rm -f "$PID_FILE"
  echo "已停止"
}

status_daemon() {
  if is_running; then
    echo "运行中: pid=$(pid_value) 端点=http://$PROBE_HOST:$PORT$INFERENCE_PATH 日志=$LOG"
    return 0
  fi
  if [ -f "$PID_FILE" ]; then
    echo "未运行（清理残留 PID 文件 $PID_FILE）"
    rm -f "$PID_FILE"
  else
    echo "未运行"
  fi
  return 1
}

foreground() {
  require_runtime
  mkdir -p "$TMP_DIR"
  echo "模型:     $MODEL"
  echo "端点:     http://$HOST:$PORT$INFERENCE_PATH"
  echo "线程:     $THREADS | 语种: $LANG | 临时目录: $TMP_DIR"
  echo
  exec whisper-server "${SERVER_ARGS[@]}"
}

ACTION="${1:-}"
if [ "$#" -gt 1 ]; then
  usage >&2
  die "只接受一个动作参数，收到多余参数: ${*:2}"
fi
case "$ACTION" in
  "")        foreground ;;
  --daemon)  start_daemon ;;
  --status)  status_daemon ;;
  --stop)    stop_daemon ;;
  -h|--help) usage ;;
  *)
    usage >&2
    die "未知参数: $ACTION（可用: --daemon | --status | --stop | --help）"
    ;;
esac
