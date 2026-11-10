<template>
  <div class="seg-transcript">
    <div v-if="!view.sentences.length" class="empty-block">
      <div class="tiny dim">{{ t('components.transcript.empty') }}</div>
    </div>

    <template v-else>
      <div class="summary">
        <span class="chip">{{
          countLabel('components.transcript.sentenceCount', view.sentences.length)
        }}</span>
        <span v-if="view.totalHits" class="chip danger">{{
          countLabel('components.transcript.hitCount', view.totalHits)
        }}</span>
        <span v-if="view.markedCount" class="chip warn">{{
          countLabel('components.transcript.markedCount', view.markedCount)
        }}</span>
        <span v-if="!view.timed" class="chip info">{{ t('components.transcript.untimed') }}</span>
        <span class="spacer"></span>
        <span v-if="view.timed" class="tiny dim">
          <el-icon :size="11"><Mouse /></el-icon> {{ t('components.transcript.seekHint') }}
        </span>
      </div>

      <!-- 双声道对话图例：左右两位说话人 + 对话样式提示 -->
      <div v-if="dialogueMode" class="channel-legend">
        <div class="speaker-pill left">
          <span class="avatar left" aria-hidden="true">L</span>
          <span class="speaker-meta">
            <span class="speaker-name">{{
              view.speakers.left || t('components.transcript.channelLeft')
            }}</span>
            <span v-if="view.speakers.left" class="speaker-sub">{{
              t('components.transcript.channelLeft')
            }}</span>
          </span>
        </div>
        <div class="legend-divider">{{ t('components.transcript.dialogueTitle') }}</div>
        <div class="speaker-pill right">
          <span class="avatar right" aria-hidden="true">R</span>
          <span class="speaker-meta">
            <span class="speaker-name">{{
              view.speakers.right || t('components.transcript.channelRight')
            }}</span>
            <span v-if="view.speakers.right" class="speaker-sub">{{
              t('components.transcript.channelRight')
            }}</span>
          </span>
        </div>
      </div>

      <ol
        ref="listRef"
        class="seg-list"
        :class="{ dialogue: dialogueMode }"
        :aria-label="dialogueMode ? t('components.transcript.dialogueTitle') : undefined"
      >
        <li
          v-for="(s, i) in view.sentences"
          :key="s.index"
          :data-idx="s.index"
          class="seg"
          :class="[
            s.side ? `side-${s.side}` : '',
            {
              active: s.index === activeIndex,
              marked: s.hasMarks,
              target: s.isTarget,
              untimed: s.start == null,
              cont: isContinuation(i)
            }
          ]"
          @click="onSeek(s)"
        >
          <span v-if="dialogueMode && s.side" class="avatar" :class="`side-${s.side}`">{{
            s.channel
          }}</span>
          <span class="rail"></span>

          <div class="bubble">
            <!-- 双声道对话：说话人 + 时间放气泡头部；单声道保持逐句列表的原有结构 -->
            <div v-if="dialogueMode && s.side" class="bubble-head">
              <span v-if="s.index === activeIndex" class="playing-dot" aria-hidden="true"></span>
              <span class="speaker">{{ speakerName(s) }}</span>
              <button
                class="time dlg-time"
                :disabled="s.start == null"
                :title="
                  s.start == null
                    ? t('components.transcript.untimedTitle')
                    : t('components.transcript.seekTo', { time: formatClock(s.start) })
                "
                @click.stop="onSeek(s)"
              >
                <el-icon class="time-icon" :size="11">
                  <component :is="s.index === activeIndex ? VideoPause : VideoPlay" />
                </el-icon>
                <span class="mono">{{ s.start != null ? formatClock(s.start) : '--:--' }}</span>
              </button>
            </div>

            <div class="bubble-main">
              <button
                v-if="!(dialogueMode && s.side)"
                class="time"
                :disabled="s.start == null"
                :title="
                  s.start == null
                    ? t('components.transcript.untimedTitle')
                    : t('components.transcript.seekTo', { time: formatClock(s.start) })
                "
                @click.stop="onSeek(s)"
              >
                <el-icon class="time-icon" :size="12">
                  <component :is="s.index === activeIndex ? VideoPause : VideoPlay" />
                </el-icon>
                <span class="mono">{{ s.start != null ? formatClock(s.start) : '--:--' }}</span>
              </button>

              <div class="body">
                <p class="text">
                  <template v-for="(p, j) in s.parts" :key="j">
                    <mark v-if="p.hit" class="mark-hit">{{ p.text }}</mark>
                    <template v-else>{{ p.text }}</template>
                  </template>
                </p>
                <div v-if="s.hasMarks" class="marks">
                  <span v-for="w in s.hitWords" :key="w" class="chip danger">{{
                    t('components.transcript.hitChip', { word: w })
                  }}</span>
                  <span v-if="s.isTarget" class="chip warn">{{
                    t('components.transcript.targetChip')
                  }}</span>
                </div>
              </div>
            </div>
          </div>
        </li>
      </ol>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from '../i18n'
import { buildSentenceView, findActiveIndex, formatClock } from '../utils/segments'
import { Mouse, VideoPause, VideoPlay } from './icons.js'

const { t } = useI18n()

/** 带数量的文案：英文按单复数取词典里的 `.one` / `.other` */
function countLabel(key, count, params = {}) {
  return t(`${key}.${count === 1 ? 'one' : 'other'}`, { ...params, count })
}

const props = defineProps({
  text: { type: String, default: '' },
  segments: { type: Array, default: () => [] },
  hits: { type: Array, default: () => [] },
  targetSentence: { type: String, default: '' },
  currentTime: { type: Number, default: 0 },
  /** 播放时是否自动滚动到当前句 */
  follow: { type: Boolean, default: true },
  /** single=逐句列表，dialogue=双声道左右对话 */
  layout: { type: String, default: 'single' }
})

const emit = defineEmits(['seek'])

const listRef = ref(null)

const view = computed(() =>
  buildSentenceView({
    text: props.text,
    segments: props.segments,
    hits: props.hits,
    targetSentence: props.targetSentence
  })
)

/** 双声道对话模式：layout=dialogue 且分段里确实带左右声道信息 */
const dialogueMode = computed(() => props.layout === 'dialogue' && view.value.dialogue)

const activeIndex = computed(() => findActiveIndex(view.value.sentences, props.currentTime))

/** 同一说话人连续发言：隐藏重复头像/收紧间距，读起来更像一段对话 */
function isContinuation(i) {
  const list = view.value.sentences
  const cur = list[i]
  const prev = list[i - 1]
  return Boolean(cur?.side && prev?.side && prev.side === cur.side)
}

function speakerName(sentence) {
  if (sentence.speaker) return sentence.speaker
  return sentence.side === 'left'
    ? t('components.transcript.channelLeft')
    : t('components.transcript.channelRight')
}

function onSeek(sentence) {
  if (sentence.start == null) return
  emit('seek', sentence.start)
}

/**
 * 仅滚动「本组件所在的、指定滚动容器」，绝不触发页面级滚动。
 *
 * 之前用 scrollIntoView({ block: 'nearest' }) 会连同外层 .shell-content 一起滚动：
 * 1280 窄屏 Review 上下堆叠时，首屏会被自动顶掉约 100px（基线就存在的回归）。
 * 同时也不能盲目找“最近的 scrollable 祖先”——窄屏时逐句列表本身不滚动，
 * 最近的可滚动祖先恰好是页面容器，同样会把首屏顶走。
 */
function scrollWithinNearest(el) {
  if (!el) return
  const container = el.closest('.transcript-scroll, .el-drawer__body, .el-dialog__body')
  if (!container) return
  if (container.scrollHeight <= container.clientHeight + 1) return
  const containerRect = container.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()
  if (elRect.top < containerRect.top) {
    container.scrollTop -= containerRect.top - elRect.top
  } else if (elRect.bottom > containerRect.bottom) {
    container.scrollTop += elRect.bottom - containerRect.bottom
  }
}

// 播放中跟随高亮：仅在开启跟随时滚动，避免打断人工翻阅
watch(activeIndex, async (idx) => {
  if (!props.follow || idx < 0) return
  await nextTick()
  const el = listRef.value?.querySelector(`[data-idx="${idx}"]`)
  scrollWithinNearest(el)
})
</script>

<style scoped>
.seg-transcript {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.summary {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12px;
}
.summary .spacer {
  flex: 1;
}

/* ---------- 双声道对话图例 ---------- */
.channel-legend {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 12px;
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, #fbfcff, #f6f8fe);
  border: 1px solid var(--border);
}
.speaker-pill {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.speaker-pill.right {
  flex-direction: row-reverse;
  text-align: right;
}
.avatar {
  width: 30px;
  height: 30px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.12);
  transition: box-shadow 0.18s ease;
}
.avatar.left {
  background: linear-gradient(135deg, #6b8ef9, #3b6ef6);
}
.avatar.right {
  background: linear-gradient(135deg, #a78bfa, #7c5cf5);
}
.speaker-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  min-width: 0;
}
.speaker-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-700);
}
.speaker-sub {
  font-size: 11px;
  color: var(--ink-400);
  white-space: nowrap;
}
.legend-divider {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-400);
  font-size: 11px;
  white-space: nowrap;
}
.legend-divider::before,
.legend-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--ink-200), transparent);
}

/* ---------- 逐句列表 ---------- */
.seg-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.seg {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 9px 12px 9px 14px;
  border-radius: var(--r-md);
  cursor: pointer;
  transition: background 0.16s ease;
  border: 1px solid transparent;
}
.seg:hover {
  background: var(--ink-50);
}
.seg.untimed {
  cursor: default;
}

/* ---------- 双声道对话：左右气泡 ---------- */
.seg-list.dialogue {
  gap: 10px;
}
.seg-list.dialogue .seg {
  gap: 9px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
}
.seg-list.dialogue .seg:hover {
  background: transparent;
}
.seg-list.dialogue .seg .rail {
  display: none;
}
.seg-list.dialogue .seg.side-right {
  flex-direction: row-reverse;
}
.seg-list.dialogue .seg.cont {
  margin-top: -6px;
}
.seg-list.dialogue .seg.cont .avatar {
  visibility: hidden;
}

.bubble {
  max-width: 84%;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fff;
  padding: 8px 12px 9px;
  transition:
    box-shadow 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease;
}
.seg-list.dialogue .bubble {
  max-width: 78%;
  padding: 9px 13px 10px;
  border-radius: 16px;
}
.seg-list.dialogue .seg.side-left .bubble {
  background: linear-gradient(180deg, #f8faff, #edf3ff);
  border-color: #dde8ff;
  border-top-left-radius: 5px;
}
.seg-list.dialogue .seg.side-right .bubble {
  background: linear-gradient(180deg, #fbf9ff, #f2eeff);
  border-color: #e6ddfc;
  border-top-right-radius: 5px;
}
.seg-list.dialogue .seg.cont.side-left .bubble {
  border-top-left-radius: 16px;
}
.seg-list.dialogue .seg.cont.side-right .bubble {
  border-top-right-radius: 16px;
}
.seg-list.dialogue .seg:hover .bubble {
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.07);
  border-color: #cdd9f6;
}
.seg-list.dialogue .seg.active .bubble {
  box-shadow:
    0 0 0 2px var(--brand-300),
    0 6px 16px rgba(59, 110, 246, 0.12);
  border-color: var(--brand-300);
}
.seg-list.dialogue .seg.active .avatar {
  box-shadow: 0 0 0 3px rgba(59, 110, 246, 0.18);
}
.seg:not(.side-left):not(.side-right) .bubble {
  border: none;
  padding: 0;
  max-width: 100%;
  background: transparent;
}

.bubble-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
}
.seg-list.dialogue .bubble-head {
  gap: 6px;
  margin-bottom: 4px;
  line-height: 1.2;
}
.seg-list.dialogue .seg.side-right .bubble-head {
  flex-direction: row-reverse;
}
.speaker {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.seg-list.dialogue .speaker {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--brand-600);
}
.seg-list.dialogue .seg.side-right .speaker {
  color: #6d4ede;
}
.playing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-500);
  flex: none;
  animation: dlg-pulse 1.2s ease-in-out infinite;
}
@keyframes dlg-pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.82);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}
.bubble-main {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.seg-list.dialogue .bubble-main {
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
}

/* 时间按钮：单声道为描边小按钮；双声道对话内为轻量时间戳（hover 变品牌色） */
.time {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--brand-600);
  font-size: 11.5px;
  font-family: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: all 0.15s ease;
}
.time:hover:not(:disabled) {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
}
.time:disabled {
  color: var(--ink-400);
  cursor: not-allowed;
  background: var(--ink-50);
}
.seg.active .time {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
}
.seg-list.dialogue .time {
  height: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink-400);
  font-size: 11px;
  font-family: var(--font-mono);
}
.seg-list.dialogue .time:hover:not(:disabled) {
  background: transparent;
  color: var(--brand-500);
}
.seg-list.dialogue .time:disabled {
  background: transparent;
  color: var(--ink-300);
}
.seg-list.dialogue .seg.active .time {
  background: transparent;
  color: var(--brand-500);
}
.time-icon {
  opacity: 0.85;
}

/* 左侧竖条：命中(红) / AI 原句(橙) */
.rail {
  position: absolute;
  left: 4px;
  top: 11px;
  bottom: 11px;
  width: 3px;
  border-radius: 3px;
  background: transparent;
}
.seg.marked .rail {
  background: linear-gradient(180deg, #f87171, #ef4444);
}
.seg.target:not(.marked) .rail {
  background: linear-gradient(180deg, #fbbf24, #f59e0b);
}
.seg.marked.target .rail {
  background: linear-gradient(180deg, #f87171, #f59e0b);
}

/* 当前播放句（逐句模式） */
.seg.active:not(.side-left):not(.side-right) {
  background: var(--brand-50);
  border-color: var(--brand-200);
}
.seg.active .text {
  color: var(--ink-800);
}

.body {
  min-width: 0;
  flex: 1;
}
.text {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink-600);
  word-break: break-word;
}
.seg-list.dialogue .text {
  line-height: 1.72;
  color: var(--ink-700);
}
.marks {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 7px;
}
.seg-list.dialogue .marks {
  margin-top: 6px;
  gap: 5px;
}
.seg-list.dialogue .marks .chip {
  height: 20px;
  font-size: 11px;
}

/* 窄屏（复检页窄栏 / 小屏）：气泡略放宽、头像缩小 */
@media (max-width: 560px) {
  .seg-list.dialogue .bubble {
    max-width: 86%;
  }
  .avatar {
    width: 26px;
    height: 26px;
    font-size: 11px;
  }
  .speaker-sub {
    display: none;
  }
}
</style>
