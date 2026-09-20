<template>
  <div class="seg-transcript">
    <div v-if="!view.sentences.length" class="empty-block">
      <div class="tiny dim">（无转写文本）</div>
    </div>

    <template v-else>
      <div class="summary">
        <span class="chip">{{ view.sentences.length }} 句</span>
        <span v-if="view.totalHits" class="chip danger">{{ view.totalHits }} 处命中</span>
        <span v-if="view.markedCount" class="chip warn">{{ view.markedCount }} 句有标记</span>
        <span v-if="!view.timed" class="chip info">无分段时长，点击跳转不可用</span>
        <span class="spacer"></span>
        <span v-if="view.timed" class="tiny dim">
          <el-icon :size="11"><Mouse /></el-icon> 点击任意句子跳转到对应音频位置
        </span>
      </div>

      <!-- 双声道图例 -->
      <div v-if="layout === 'dialogue' && view.dialogue" class="channel-legend">
        <div class="legend-side left">
          <span class="ch-badge">L</span>
          <span class="ch-name">{{ view.speakers.left || '左声道' }}</span>
          <span class="ch-hint">左声道</span>
        </div>
        <div class="legend-line"></div>
        <div class="legend-side right">
          <span class="ch-badge">R</span>
          <span class="ch-name">{{ view.speakers.right || '右声道' }}</span>
          <span class="ch-hint">右声道</span>
        </div>
      </div>

      <ol
        ref="listRef"
        class="seg-list"
        :class="{ dialogue: layout === 'dialogue' && view.dialogue }"
      >
        <li
          v-for="s in view.sentences"
          :key="s.index"
          :data-idx="s.index"
          class="seg"
          :class="[
            s.side ? `side-${s.side}` : '',
            {
              active: s.index === activeIndex,
              marked: s.hasMarks,
              target: s.isTarget,
              untimed: s.start == null
            }
          ]"
          @click="onSeek(s)"
        >
          <span class="rail"></span>

          <div class="bubble">
            <div v-if="layout === 'dialogue' && s.side" class="bubble-head">
              <span class="ch-badge sm">{{ s.channel }}</span>
              <span class="speaker">{{ s.speaker || (s.side === 'left' ? '左声道' : '右声道') }}</span>
            </div>

            <div class="bubble-main">
              <button
                class="time"
                :disabled="s.start == null"
                :title="s.start == null ? '该句无时间轴' : `跳转到 ${formatClock(s.start)}`"
                @click.stop="onSeek(s)"
              >
                <el-icon class="time-icon" :size="12">
                  <component :is="s.index === activeIndex ? 'VideoPause' : 'VideoPlay'" />
                </el-icon>
                <span class="mono">{{ s.start != null ? formatClock(s.start) : '--:--' }}</span>
              </button>

              <div class="body">
                <p class="text">
                  <template v-for="(p, i) in s.parts" :key="i">
                    <mark v-if="p.hit" class="mark-hit">{{ p.text }}</mark>
                    <template v-else>{{ p.text }}</template>
                  </template>
                </p>
                <div v-if="s.hasMarks" class="marks">
                  <span v-for="w in s.hitWords" :key="w" class="chip danger">命中 · {{ w }}</span>
                  <span v-if="s.isTarget" class="chip warn">AI 判违规原句</span>
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
import { buildSentenceView, findActiveIndex, formatClock } from '../utils/segments'

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

const activeIndex = computed(() => findActiveIndex(view.value.sentences, props.currentTime))

function onSeek(sentence) {
  if (sentence.start == null) return
  emit('seek', sentence.start)
}

// 播放中跟随高亮：仅在开启跟随时滚动，避免打断人工翻阅
watch(activeIndex, async (idx) => {
  if (!props.follow || idx < 0) return
  await nextTick()
  const el = listRef.value?.querySelector(`[data-idx="${idx}"]`)
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
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

/* 声道图例 */
.channel-legend {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  border-radius: var(--r-md);
  background: linear-gradient(90deg, rgba(59, 110, 246, 0.08), rgba(100, 116, 139, 0.08));
  border: 1px solid var(--border);
}
.legend-side {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.legend-side.right {
  flex-direction: row-reverse;
}
.legend-line {
  flex: 1;
  height: 1px;
  background: repeating-linear-gradient(90deg, var(--ink-300) 0 4px, transparent 4px 8px);
}
.ch-badge {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  font-family: var(--font-mono);
  color: #fff;
}
.ch-badge.sm {
  width: 16px;
  height: 16px;
  font-size: 10px;
  border-radius: 5px;
}
.legend-side.left .ch-badge,
.seg.side-left .ch-badge {
  background: var(--brand-500);
}
.legend-side.right .ch-badge,
.seg.side-right .ch-badge {
  background: #64748b;
}
.ch-name {
  font-weight: 600;
  color: var(--ink-700);
}
.ch-hint {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

/* 逐句列表 */
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

/* 双声道：左右分栏 */
.seg-list.dialogue .seg {
  padding: 1px 4px;
  background: transparent;
  border: none;
}
.seg-list.dialogue .seg .rail {
  display: none;
}
.seg-list.dialogue .seg.side-right {
  justify-content: flex-end;
}
.bubble {
  max-width: 84%;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fff;
  padding: 8px 12px 9px;
  transition: box-shadow 0.18s ease, border-color 0.18s ease, background 0.18s ease;
}
.seg-list.dialogue .seg.side-left .bubble {
  background: linear-gradient(180deg, #f6f9ff, #fff);
  border-color: var(--brand-200);
  border-top-left-radius: 4px;
}
.seg-list.dialogue .seg.side-right .bubble {
  background: linear-gradient(180deg, #f8fafc, #fff);
  border-color: #dbe2ec;
  border-top-right-radius: 4px;
}
.seg-list.dialogue .seg.side-left.active .bubble,
.seg-list.dialogue .seg.side-right.active .bubble {
  box-shadow: 0 0 0 2px var(--brand-300);
  border-color: var(--brand-400);
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
.seg.side-right .bubble-head {
  flex-direction: row-reverse;
}
.speaker {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.bubble-main {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.seg.side-right .bubble-main {
  flex-direction: row-reverse;
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
.time-icon {
  opacity: 0.85;
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
.marks {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 7px;
}
</style>
