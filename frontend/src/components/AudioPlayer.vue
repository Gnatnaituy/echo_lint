<template>
  <div class="audio-player" :class="{ 'is-disabled': !src }">
    <button
      type="button"
      class="play-toggle"
      :disabled="!src"
      :aria-label="playing ? t('components.audioPlayer.pause') : t('components.audioPlayer.play')"
      :title="playing ? t('components.audioPlayer.pause') : t('components.audioPlayer.play')"
      @click="togglePlay"
    >
      <el-icon :size="14">
        <component :is="playing ? VideoPause : VideoPlay" />
      </el-icon>
    </button>

    <span class="clock num">{{ formatClock(currentTime) }}</span>

    <input
      class="seek"
      type="range"
      min="0"
      :max="duration || 0"
      step="0.05"
      :value="currentTime"
      :disabled="!src || !duration"
      :style="{ '--seek-pct': seekPercent }"
      :aria-label="t('components.audioPlayer.seekProgress')"
      @input="onSeekInput"
    />

    <span class="clock total num">{{ formatClock(duration) }}</span>

    <!-- 原生控件隐藏，仅保留音频解码能力；视觉由上面的自定义控件承担 -->
    <audio
      v-if="src"
      ref="audioEl"
      :src="src"
      preload="metadata"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onMeta"
      @play="playing = true"
      @pause="playing = false"
      @ended="onEnded"
    ></audio>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { useI18n } from '../i18n'
import { formatClock } from '../utils/segments'

const { t } = useI18n()

const props = defineProps({
  src: { type: String, default: '' },
  /** 音频元数据未就绪时的兜底总时长（秒） */
  fallbackDuration: { type: Number, default: 0 }
})

const emit = defineEmits(['time-update', 'ended'])

const audioEl = ref(null)
const playing = ref(false)
const currentTime = ref(0)
const mediaDuration = ref(0)
/** 元数据就绪前的 seek 请求，loadedmetadata 后补执行 */
let pendingSeek = null

const duration = computed(() => {
  const value = Number(mediaDuration.value) || Number(props.fallbackDuration) || 0
  return Number.isFinite(value) && value > 0 ? value : 0
})
const seekPercent = computed(() => {
  if (!duration.value) return '0%'
  const pct = Math.min(100, Math.max(0, (currentTime.value / duration.value) * 100))
  return `${pct}%`
})

function setMediaTime(el, time) {
  try {
    el.currentTime = time
    pendingSeek = null
  } catch {
    pendingSeek = time
  }
}

function onMeta(e) {
  const value = e?.target?.duration
  if (Number.isFinite(value) && value > 0) mediaDuration.value = value
  const el = audioEl.value
  if (el && pendingSeek != null) setMediaTime(el, pendingSeek)
}

function onTimeUpdate(e) {
  currentTime.value = Number(e?.target?.currentTime) || 0
  emit('time-update', currentTime.value)
}

function onEnded() {
  playing.value = false
  emit('ended')
}

function togglePlay() {
  const el = audioEl.value
  if (!el) return
  if (el.paused) el.play?.()?.catch?.(() => {})
  else el.pause?.()
}

function onSeekInput(e) {
  const time = Number(e.target.value) || 0
  currentTime.value = time
  const el = audioEl.value
  if (el) setMediaTime(el, time)
  emit('time-update', time)
}

/** 供父组件（逐句跳转等）调用：跳到指定秒数并自动播放 */
function seekTo(time, autoPlay = true) {
  const target = Math.max(0, Number(time) || 0)
  currentTime.value = target
  const el = audioEl.value
  if (el) {
    setMediaTime(el, target)
    if (autoPlay) el.play?.()?.catch?.(() => {})
  }
  emit('time-update', target)
}

function play() {
  audioEl.value?.play?.()?.catch?.(() => {})
}

function pause() {
  audioEl.value?.pause?.()
}

defineExpose({ seekTo, play, pause })

// 切换声道 / 换条录音：重置播放状态（新 src 由 <audio> 自动加载）
watch(
  () => props.src,
  () => {
    playing.value = false
    currentTime.value = 0
    mediaDuration.value = 0
    pendingSeek = null
    emit('time-update', 0)
  }
)
</script>

<style scoped>
.audio-player {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  height: 40px;
  padding: 0 14px 0 7px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff, #f8fafd);
  box-shadow: var(--sh-xs);
}
.audio-player.is-disabled {
  opacity: 0.55;
}
.play-toggle {
  width: 28px;
  height: 28px;
  flex: none;
  border: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  padding: 0;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 2px 8px rgba(59, 110, 246, 0.28);
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}
.play-toggle:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 3px 10px rgba(59, 110, 246, 0.36);
}
.play-toggle:disabled {
  cursor: default;
  background: var(--ink-300);
  box-shadow: none;
}
.clock {
  flex: none;
  min-width: 34px;
  font-size: 12px;
  color: var(--ink-600);
  letter-spacing: 0.01em;
}
.clock.total {
  color: var(--el-text-color-placeholder);
  text-align: right;
}
.seek {
  flex: 1;
  min-width: 60px;
  height: 4px;
  margin: 0;
  appearance: none;
  -webkit-appearance: none;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--brand-500) 0 var(--seek-pct, 0%),
    var(--ink-200) var(--seek-pct, 0%) 100%
  );
  cursor: pointer;
}
.seek:disabled {
  cursor: default;
}
.seek::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--brand-500);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.2);
}
.seek::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--brand-500);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.2);
}
.seek:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 5px;
}
audio {
  display: none;
}
</style>
