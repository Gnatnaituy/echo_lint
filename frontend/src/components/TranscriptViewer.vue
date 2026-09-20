<template>
  <div class="transcript-viewer">
    <!-- AI 复筛目标句提示 -->
    <el-alert
      v-if="targetSentence"
      :title="'判违规原句：' + targetSentence"
      type="warning"
      :closable="false"
      class="target-alert"
    />
    <p class="transcript-text">
      <template v-for="(seg, i) in segments" :key="i">
        <span :class="seg.hit ? 'hit' : ''">{{ seg.text }}</span>
      </template>
    </p>
    <div v-if="hits && hits.length" class="hit-legend">
      <span v-for="(h, i) in hits" :key="i" class="hit-chip">
        命中 #{{ i + 1 }}: <b>{{ h.word }}</b> [{{ h.start }}, {{ h.end }})
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  text: { type: String, default: '' },
  hits: { type: Array, default: () => [] },
  targetSentence: { type: String, default: '' }
})

// 将转写文本按命中区间切割并合并重叠区间
const segments = computed(() => {
  const text = props.text || ''
  if (!props.hits || !props.hits.length) {
    return [{ text, hit: false }]
  }
  const ranges = props.hits
    .map((h) => ({ start: h.start, end: h.end }))
    .filter((r) => r.start >= 0 && r.end <= text.length && r.end > r.start)
    .sort((a, b) => a.start - b.start)
  const merged = []
  let cur = null
  for (const r of ranges) {
    if (!cur) {
      cur = { start: r.start, end: r.end }
    } else if (r.start <= cur.end) {
      cur.end = Math.max(cur.end, r.end)
    } else {
      merged.push(cur)
      cur = { start: r.start, end: r.end }
    }
  }
  if (cur) merged.push(cur)
  if (!merged.length) return [{ text, hit: false }]

  const out = []
  let pos = 0
  for (const r of merged) {
    if (r.start > pos) out.push({ text: text.slice(pos, r.start), hit: false })
    out.push({ text: text.slice(r.start, r.end), hit: true })
    pos = r.end
  }
  if (pos < text.length) out.push({ text: text.slice(pos), hit: false })
  return out
})
</script>

<style scoped>
.transcript-text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.9;
  font-size: 14px;
  margin: 8px 0;
}
.hit {
  background: #fde2e2;
  color: #c45656;
  font-weight: 700;
  border-radius: 3px;
  padding: 0 2px;
}
.target-alert {
  margin-bottom: 10px;
}
.hit-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.hit-chip {
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  color: #c45656;
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
}
</style>