#!/usr/bin/env bash
# 启动本机 whisper.cpp 转写服务，并把端点伪装成 OpenAI 兼容的
# POST /v1/audio/transcriptions，从而让 EchoLint 后端零代码改道到本地。
#
# 依赖：
#   brew install whisper.cpp ffmpeg
#   模型：~/models/whisper/ggml-large-v3-turbo.bin（可用 WHISPER_MODEL 覆盖）
#
# 用法：
#   tools/whisper-server.sh              # 前台启动
#   tools/whisper-server.sh --daemon     # 后台启动，日志写 /tmp/whisper-server.log
#
# 启动后把下面两行写进 .env，再 `docker compose up -d` 即可：
#   WHISPER_BASE_URL=http://host.docker.internal:9900
#   WHISPER_API_KEY=local        # whisper.cpp 本身不校验，但后端要求非空
set -euo pipefail

MODEL="${WHISPER_MODEL:-$HOME/models/whisper/ggml-large-v3-turbo.bin}"
HOST="${WHISPER_HOST:-127.0.0.1}"
PORT="${WHISPER_PORT:-9900}"
THREADS="${WHISPER_THREADS:-8}"          # M1 Pro: 8 个性能核
LANG="${WHISPER_LANG:-auto}"             # auto = 中英混合自动识别
TMP_DIR="${WHISPER_TMP_DIR:-/tmp/whisper-tmp}"

# 关键：把默认的 /inference 改成 OpenAI 的路径，后端无需任何改动
INFERENCE_PATH="/v1/audio/transcriptions"

die() { echo "错误: $*" >&2; exit 1; }

command -v whisper-server >/dev/null || die "未找到 whisper-server，请先执行: brew install whisper.cpp"
[ -f "$MODEL" ] || die "模型不存在: $MODEL
下载命令:
  mkdir -p \"\$(dirname '$MODEL')\" && curl -L -o '$MODEL' \\
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin"

# --convert 需要 ffmpeg 来解码 mp3/m4a 等非 16k WAV 格式
command -v ffmpeg >/dev/null || die "未找到 ffmpeg（--convert 依赖它解码 mp3/m4a），请先执行: brew install ffmpeg"

mkdir -p "$TMP_DIR"
command -v lsof >/dev/null && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 \
  && die "端口 $PORT 已被占用（可能已在运行）"

echo "模型:     $MODEL"
echo "端点:     http://$HOST:$PORT$INFERENCE_PATH"
echo "线程:     $THREADS | 语种: $LANG | 临时目录: $TMP_DIR"
echo

ARGS=(
  -m "$MODEL"
  --host "$HOST" --port "$PORT"
  --inference-path "$INFERENCE_PATH"
  -t "$THREADS"
  -l "$LANG"
  --convert --tmp-dir "$TMP_DIR"
)

if [ "${1:-}" = "--daemon" ]; then
  LOG="${WHISPER_LOG:-/tmp/whisper-server.log}"
  nohup whisper-server "${ARGS[@]}" >"$LOG" 2>&1 &
  echo "已后台启动 (pid $!)，日志: $LOG"
  echo "就绪判定: 直到出现 'server is listening' 或直接试"
  echo "  curl -s -o /dev/null -w '%{http_code}\\n' -X POST http://$HOST:$PORT$INFERENCE_PATH -F file=@音频 -F model=whisper-1"
else
  exec whisper-server "${ARGS[@]}"
fi
