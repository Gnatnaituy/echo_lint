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

      <ol ref="listRef" class="seg-list">
        <li
          v-for="s in view.sentences"
          :key="s.index"
          :data-idx="s.index"
          class="seg"
          :class="{ active: s.index === activeIndex, marked: s.hasMarks, target: s.isTarget, untimed: s.start == null }"
          @click="onSeek(s)"
        >
          <span class="rail"></span>

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
  follow: { type: Boolean, default: true }
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

/* 当前播放句 */
.seg.active {
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
