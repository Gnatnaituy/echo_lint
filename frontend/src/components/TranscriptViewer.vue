<template>
  <div class="transcript">
    <div v-if="targetSentence" class="target">
      <el-icon class="target-icon"><Aim /></el-icon>
      <div>
        <div class="target-label">AI 判定违规原句</div>
        <div class="target-text">“{{ targetSentence }}”</div>
      </div>
    </div>

    <div class="body">
      <p class="text">
        <template v-for="(seg, i) in segments" :key="i">
          <mark v-if="seg.hit" class="mark-hit">{{ seg.text }}</mark>
          <template v-else>{{ seg.text }}</template>
        </template>
      </p>
    </div>

    <div v-if="hits && hits.length" class="legend">
      <span class="legend-title">DFA 命中 {{ hits.length }} 处</span>
      <span v-for="(h, i) in hits" :key="i" class="legend-chip">
        <b>{{ h.word }}</b>
        <span class="dim mono">[{{ h.start }},{{ h.end }})</span>
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
.transcript {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.target {
  display: flex;
  gap: 10px;
  padding: 11px 14px;
  border-radius: var(--r-md);
  background: linear-gradient(90deg, #fff7e8, #fffdf8);
  border: 1px solid #f7dfae;
}
.target-icon {
  color: var(--warn);
  margin-top: 2px;
  flex: none;
}
.target-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #b06a06;
  text-transform: uppercase;
}
.target-text {
  font-size: 13px;
  color: #7c4a04;
  margin-top: 2px;
  line-height: 1.6;
}

.body {
  background: #fbfcfe;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 14px 16px;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 2;
  font-size: 13.5px;
  margin: 0;
  color: var(--ink-600);
}

.legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.legend-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.legend-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #c92c2c;
  font-size: 12px;
}
</style>
