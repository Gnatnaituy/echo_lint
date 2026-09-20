<template>
  <div class="chart">
    <div class="chart-head">
      <span class="peak">
        峰值 <b class="num">{{ maxCount }}</b> 条
      </span>
      <span class="total tiny dim">
        近 7 日合计 <b class="num">{{ totalCount }}</b> 条
      </span>
    </div>

    <div class="plot">
      <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="svg">
        <defs>
          <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#3b6ef6" stop-opacity="0.26" />
            <stop offset="100%" stop-color="#3b6ef6" stop-opacity="0" />
          </linearGradient>
        </defs>

        <line
          v-for="(g, i) in gridLines"
          :key="`g${i}`"
          :x1="pad.l"
          :x2="W - pad.r"
          :y1="g"
          :y2="g"
          class="grid"
          vector-effect="non-scaling-stroke"
        />

        <path :d="areaPath" fill="url(#trendFill)" />
        <path :d="linePath" class="line" vector-effect="non-scaling-stroke" />

        <template v-if="hover >= 0">
          <line
            :x1="points[hover].x"
            :x2="points[hover].x"
            :y1="pad.t"
            :y2="H - pad.b"
            class="guide"
            vector-effect="non-scaling-stroke"
          />
        </template>

        <rect
          v-for="(p, i) in points"
          :key="`h${i}`"
          :x="p.x - band / 2"
          :y="pad.t"
          :width="band"
          :height="H - pad.t - pad.b"
          fill="transparent"
          @mouseenter="hover = i"
          @mouseleave="hover = -1"
        />
      </svg>

      <!-- 悬停点（HTML 层，避免 viewBox 拉伸变形） -->
      <div
        v-if="hover >= 0"
        class="hover-dot"
        :style="{ left: dotLeft, top: dotTop }"
      ></div>
      <div v-if="hover >= 0" class="tooltip" :style="tooltipStyle">
        <div class="tt-date">{{ data[hover].date }}</div>
        <div class="tt-value"><b class="num">{{ data[hover].count }}</b> 条录音</div>
      </div>
    </div>

    <div class="xaxis">
      <span v-for="(d, i) in data" :key="i" class="x-label" :class="{ on: hover === i }">
        {{ d.date.slice(5) }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] },
  height: { type: Number, default: 190 }
})

const W = 700
const pad = { t: 18, r: 16, b: 10, l: 16 }
const H = computed(() => props.height)
const hover = ref(-1)

const maxCount = computed(() => Math.max(0, ...props.data.map((d) => d.count)))
const totalCount = computed(() => props.data.reduce((s, d) => s + d.count, 0))

const scaleMax = computed(() => {
  const m = Math.max(1, maxCount.value)
  const pow = Math.pow(10, Math.floor(Math.log10(m)))
  const n = m / pow
  const nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10
  return nice * pow
})

const band = computed(() => (W - pad.l - pad.r) / Math.max(1, props.data.length))

const points = computed(() =>
  props.data.map((d, i) => ({
    x: pad.l + band.value * i + band.value / 2,
    y: pad.t + (1 - d.count / scaleMax.value) * (H.value - pad.t - pad.b),
    count: d.count
  }))
)

const gridLines = computed(() => {
  const inner = H.value - pad.t - pad.b
  return [0, 1, 2, 3].map((i) => pad.t + (inner / 3) * i)
})

function smoothPath(pts) {
  if (!pts.length) return ''
  if (pts.length === 1) return `M ${pts[0].x} ${pts[0].y}`
  let d = `M ${pts[0].x} ${pts[0].y}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i]
    const p1 = pts[i]
    const p2 = pts[i + 1]
    const p3 = pts[i + 2] || p2
    const t = 0.18
    const c1x = p1.x + (p2.x - p0.x) * t
    const c1y = p1.y + (p2.y - p0.y) * t
    const c2x = p2.x - (p3.x - p1.x) * t
    const c2y = p2.y - (p3.y - p1.y) * t
    d += ` C ${c1x.toFixed(2)} ${c1y.toFixed(2)}, ${c2x.toFixed(2)} ${c2y.toFixed(2)}, ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`
  }
  return d
}

const linePath = computed(() => smoothPath(points.value))
const areaPath = computed(() => {
  const pts = points.value
  if (!pts.length) return ''
  const base = H.value - pad.b
  return `${smoothPath(pts)} L ${pts[pts.length - 1].x} ${base} L ${pts[0].x} ${base} Z`
})

const dotLeft = computed(() => (points.value[hover.value].x / W) * 100 + '%')
const dotTop = computed(() => (points.value[hover.value].y / H.value) * 100 + '%')
const tooltipStyle = computed(() => {
  const leftPct = (points.value[hover.value].x / W) * 100
  return {
    left: `${leftPct}%`,
    transform: leftPct > 72 ? 'translate(-100%, -8px)' : leftPct < 12 ? 'translate(0, -8px)' : 'translate(-50%, -8px)'
  }
})
</script>

<style scoped>
.chart {
  width: 100%;
}
.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.peak {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.peak b {
  color: var(--ink-700);
  margin-left: 2px;
}

.plot {
  position: relative;
  width: 100%;
}
.svg {
  width: 100%;
  height: v-bind('height + "px"');
  display: block;
  overflow: visible;
}
.grid {
  stroke: #eef1f6;
  stroke-width: 1;
}
.line {
  fill: none;
  stroke: var(--brand-500);
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.guide {
  stroke: var(--brand-300);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}
.hover-dot {
  position: absolute;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  border: 2.5px solid var(--brand-500);
  transform: translate(-50%, -50%);
  box-shadow: 0 2px 8px rgba(59, 110, 246, 0.35);
  pointer-events: none;
}
.tooltip {
  position: absolute;
  top: 0;
  background: rgba(15, 23, 42, 0.94);
  color: #fff;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: var(--sh-md);
  z-index: 2;
}
.tt-date {
  color: rgba(255, 255, 255, 0.6);
  font-size: 11px;
}
.tt-value b {
  font-size: 14px;
  margin-right: 2px;
}

.xaxis {
  display: flex;
  margin-top: 6px;
}
.x-label {
  flex: 1;
  text-align: center;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  transition: color 0.15s ease;
}
.x-label.on {
  color: var(--brand-600);
  font-weight: 600;
}
</style>
