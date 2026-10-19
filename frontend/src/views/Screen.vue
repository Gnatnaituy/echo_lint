<template>
  <div class="screen">
    <header class="screen-head">
      <div class="head-left">
        <div class="head-mark"><el-icon :size="22"><Monitor /></el-icon></div>
        <div class="head-text">
          <div class="screen-title">{{ t('screen.title') }}</div>
          <div class="screen-sub">
            {{ t('screen.autoRefresh', { seconds: REFRESH_SECONDS }) }}
            <span v-if="lastUpdatedText">· {{ t('screen.updatedAt', { time: lastUpdatedText }) }}</span>
          </div>
        </div>
      </div>
      <div class="head-right">
        <div class="clock">
          <span class="clock-time num">{{ clockTime }}</span>
          <span class="clock-date">{{ clockDate }}</span>
        </div>
        <span class="live"><span class="pulse"></span>{{ t('screen.live') }}</span>
        <button type="button" class="exit-btn" :aria-label="t('screen.exit')" @click="exit">
          <el-icon :size="14"><SwitchButton /></el-icon>
          {{ t('screen.exit') }}
        </button>
      </div>
    </header>

    <main v-loading="loading" class="screen-body">
      <div v-if="error" class="screen-error">
        <el-icon :size="18"><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <button type="button" class="exit-btn" @click="load()">{{ t('common.retry') }}</button>
      </div>

      <!-- KPI -->
      <div class="kpi-row">
        <div class="kpi">
          <div class="kpi-label">{{ t('screen.kpiTotal') }}</div>
          <div class="kpi-value num">{{ formatCount(metrics.total) }}</div>
          <div class="kpi-sub">{{ t('screen.countUnit') }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">{{ t('screen.kpiToday') }}</div>
          <div class="kpi-value num">{{ formatCount(metrics.today) }}</div>
          <div class="kpi-sub">{{ t('screen.countUnit') }}</div>
        </div>
        <div class="kpi warn">
          <div class="kpi-label">{{ t('screen.kpiNeedsReview') }}</div>
          <div class="kpi-value num">{{ formatCount(metrics.needsReview) }}</div>
          <div class="kpi-sub">{{ t('screen.attention') }}</div>
        </div>
        <div class="kpi danger">
          <div class="kpi-label">{{ t('screen.kpiViolationRate') }}</div>
          <div class="kpi-value num">{{ formatRate(metrics.violationRate) }}</div>
          <div class="kpi-sub">{{ t('screen.rateNote') }}</div>
        </div>
      </div>

      <!-- 趋势 + 违规类型分布 -->
      <div class="mid-row">
        <section class="scr-panel">
          <div class="panel-title">{{ t('screen.trend7d') }}</div>
          <TrendChart v-if="trend.length" :data="trend" :height="250" />
          <div v-else class="scr-empty">{{ t('screen.empty') }}</div>
        </section>

        <section class="scr-panel">
          <div class="panel-title">{{ t('screen.violationDist') }}</div>
          <div v-if="violationRows.length" class="dist-list">
            <div v-for="row in violationRows" :key="row.key" class="dist-row">
              <div class="dist-top">
                <span class="dist-label">{{ violationLabel(row) }}</span>
                <span class="dist-count num">{{ formatCount(row.count) }}</span>
              </div>
              <div class="dist-track">
                <div class="dist-fill" :style="{ width: rateWidth(row.percent) }"></div>
              </div>
            </div>
          </div>
          <div v-else class="scr-empty">{{ t('screen.empty') }}</div>
        </section>
      </div>

      <!-- Top 坐席 / 团队 -->
      <div class="bottom-row">
        <section class="scr-panel">
          <div class="panel-title">{{ t('screen.topAgents') }}</div>
          <div v-if="agentRows.length" class="rank-list">
            <div v-for="(row, i) in agentRows" :key="`a-${row.name}`" class="rank-row">
              <span class="rank-index num">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="rank-name ellipsis" :title="row.name">{{ row.name }}</span>
              <div class="rank-track"><div class="rank-fill" :style="{ width: rankWidth(row.count, agentMax) }"></div></div>
              <span class="rank-count num">{{ formatCount(row.count) }}</span>
            </div>
          </div>
          <div v-else class="scr-empty">{{ t('screen.empty') }}</div>
        </section>

        <section class="scr-panel">
          <div class="panel-title">{{ t('screen.topTeams') }}</div>
          <div v-if="teamRows.length" class="rank-list">
            <div v-for="(row, i) in teamRows" :key="`t-${row.name}`" class="rank-row">
              <span class="rank-index num">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="rank-name ellipsis" :title="row.name">{{ row.name }}</span>
              <div class="rank-track"><div class="rank-fill team" :style="{ width: rankWidth(row.count, teamMax) }"></div></div>
              <span class="rank-count num">{{ formatCount(row.count) }}</span>
            </div>
          </div>
          <div v-else class="scr-empty">{{ t('screen.empty') }}</div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import TrendChart from '../components/TrendChart.vue'
import { useI18n } from '../i18n'
import {
  formatCount,
  formatRate,
  normalizeScreenMetrics,
  normalizeScreenTrend,
  normalizeTopRows,
  normalizeViolationDistribution
} from '../utils/screen'

const REFRESH_SECONDS = 15
const router = useRouter()
const { t, locale } = useI18n()

const overview = ref(null)
const report = ref(null)
const loading = ref(false)
const error = ref('')
const lastUpdatedText = ref('')
const trend = ref([])
const violationRows = ref([])
const agentRows = ref([])
const teamRows = ref([])

const metrics = computed(() => normalizeScreenMetrics(overview.value, report.value))
const agentMax = computed(() => Math.max(1, ...agentRows.value.map((r) => r.count)))
const teamMax = computed(() => Math.max(1, ...teamRows.value.map((r) => r.count)))

const violationLabel = (row) => row?.label || row?.key || '—'
const rateWidth = (percent) => `${Math.max(2, Math.round((Number(percent) || 0) * 100))}%`
const rankWidth = (count, max) =>
  `${Math.max(4, Math.round((Number(count) || 0) / Math.max(1, Number(max) || 1) * 100))}%`

/* ---------- 时钟 ---------- */

const now = ref(new Date())
let clockTimer = null
const clockTime = computed(() => {
  const d = now.value
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})
const clockDate = computed(() => {
  try {
    return now.value.toLocaleDateString(locale.value === 'zh' ? 'zh-CN' : 'en-GB', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      weekday: 'short'
    })
  } catch {
    return now.value.toDateString()
  }
})

/* ---------- 数据加载 ---------- */

function toDateParam(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function reportRange() {
  const to = new Date()
  const from = new Date()
  from.setDate(to.getDate() - 29)
  return { from: toDateParam(from), to: toDateParam(to) }
}

function nowText() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function errorMessage(reason) {
  const status = reason?.status
  if (status === 403) return '当前账号无权限查看数据大屏（请重新登录）'
  if (status === 404) return '后端统计接口不可用（HTTP 404），请确认后端版本'
  return reason?.message || '数据加载失败，请稍后重试'
}

async function load({ background = false } = {}) {
  if (!background) loading.value = true
  error.value = ''
  try {
    // 允许部分失败：overview 失败时仍展示 report 的分布/Top 数据（反之亦然）
    const [ov, rp] = await Promise.allSettled([
      api.overview(),
      api.statsReport(reportRange())
    ])
    overview.value = ov.status === 'fulfilled' ? ov.value : null
    report.value = rp.status === 'fulfilled' ? rp.value : null
    if (ov.status === 'rejected' && rp.status === 'rejected') {
      error.value = errorMessage(ov.reason)
    }
    trend.value = normalizeScreenTrend(overview.value)
    violationRows.value = normalizeViolationDistribution(report.value)
    agentRows.value = normalizeTopRows(report.value?.topAgents, 5)
    teamRows.value = normalizeTopRows(report.value?.topTeams, 5)
    lastUpdatedText.value = nowText()
  } finally {
    loading.value = false
  }
}

/* ---------- 自动刷新 / 退出 ---------- */

let refreshTimer = null
function tick() {
  if (document.hidden) return
  load({ background: true })
}

function exit() {
  router.push('/')
}

function onKeydown(event) {
  if (event.key === 'Escape') exit()
}

onMounted(() => {
  load()
  clockTimer = setInterval(() => {
    now.value = new Date()
  }, 1000)
  refreshTimer = setInterval(tick, REFRESH_SECONDS * 1000)
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  clearInterval(clockTimer)
  clearInterval(refreshTimer)
  clockTimer = null
  refreshTimer = null
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.screen {
  position: fixed;
  inset: 0;
  overflow: auto;
  padding: clamp(14px, 1.4vw, 26px) clamp(16px, 1.8vw, 34px) clamp(14px, 1.4vw, 26px);
  background:
    radial-gradient(1100px 520px at 8% -8%, rgba(59, 110, 246, 0.28) 0%, rgba(7, 12, 26, 0) 62%),
    radial-gradient(900px 420px at 100% 0%, rgba(22, 163, 74, 0.12) 0%, rgba(7, 12, 26, 0) 58%),
    linear-gradient(180deg, #0a1224 0%, #070c18 100%);
  color: #dbe6ff;
  display: flex;
  flex-direction: column;
  gap: clamp(12px, 1.2vw, 22px);
}

/* 头部 */
.screen-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.head-mark {
  width: clamp(40px, 3vw, 54px);
  height: clamp(40px, 3vw, 54px);
  flex: none;
  border-radius: 14px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #3b6ef6, #7aa2ff);
  box-shadow: 0 10px 30px rgba(59, 110, 246, 0.45);
}
.screen-title {
  font-size: clamp(19px, 1.7vw, 32px);
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #f2f6ff;
  line-height: 1.25;
}
.screen-sub {
  margin-top: 3px;
  font-size: clamp(11px, 0.78vw, 14px);
  color: rgba(196, 210, 240, 0.62);
}
.head-right {
  display: flex;
  align-items: center;
  gap: clamp(12px, 1.2vw, 22px);
}
.clock {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.2;
}
.clock-time {
  font-size: clamp(22px, 1.9vw, 34px);
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}
.clock-date {
  font-size: clamp(10px, 0.72vw, 13px);
  color: rgba(196, 210, 240, 0.6);
}
.live {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 13px;
  border-radius: 999px;
  border: 1px solid rgba(34, 197, 94, 0.4);
  background: rgba(22, 163, 74, 0.14);
  color: #4ade80;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}
.pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.6);
  animation: pulse 1.8s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.55); }
  70% { box-shadow: 0 0 0 9px rgba(34, 197, 94, 0); }
  100% { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0); }
}
.exit-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 16px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.4);
  background: rgba(15, 23, 42, 0.5);
  color: #dbe6ff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
}
.exit-btn:hover {
  border-color: rgba(122, 162, 255, 0.8);
  color: #fff;
  background: rgba(59, 110, 246, 0.28);
}

/* 主体 */
.screen-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: clamp(12px, 1.1vw, 20px);
}
.screen-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-radius: 12px;
  background: rgba(220, 38, 38, 0.16);
  border: 1px solid rgba(248, 113, 113, 0.4);
  color: #fecaca;
  font-size: 13px;
}
.screen-error .exit-btn {
  margin-left: auto;
}

/* KPI */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: clamp(12px, 1.1vw, 20px);
}
.kpi {
  position: relative;
  padding: clamp(14px, 1.2vw, 24px);
  border-radius: 16px;
  border: 1px solid rgba(122, 162, 255, 0.2);
  background: linear-gradient(180deg, rgba(30, 41, 74, 0.82) 0%, rgba(15, 23, 42, 0.72) 100%);
  overflow: hidden;
}
.kpi::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: #3b6ef6;
}
.kpi.warn::before { background: #f59e0b; }
.kpi.danger::before { background: #ef4444; }
.kpi-label {
  font-size: clamp(12px, 0.85vw, 15px);
  color: rgba(196, 210, 240, 0.72);
}
.kpi-value {
  margin-top: 8px;
  font-size: clamp(28px, 2.6vw, 52px);
  font-weight: 700;
  line-height: 1.1;
  color: #fff;
  letter-spacing: -0.01em;
}
.kpi.warn .kpi-value { color: #fbbf24; }
.kpi.danger .kpi-value { color: #f87171; }
.kpi-sub {
  margin-top: 5px;
  font-size: clamp(10px, 0.7vw, 12.5px);
  color: rgba(196, 210, 240, 0.5);
}

/* 面板 */
.scr-panel {
  padding: clamp(14px, 1.1vw, 22px);
  border-radius: 16px;
  border: 1px solid rgba(122, 162, 255, 0.18);
  background: linear-gradient(180deg, rgba(23, 33, 60, 0.78) 0%, rgba(11, 18, 34, 0.7) 100%);
  min-width: 0;
}
.panel-title {
  font-size: clamp(13px, 0.95vw, 17px);
  font-weight: 600;
  color: #e7eeff;
  letter-spacing: 0.02em;
}
.panel-title::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 13px;
  margin-right: 8px;
  border-radius: 2px;
  background: linear-gradient(180deg, #7aa2ff, #3b6ef6);
  vertical-align: -1px;
}
.mid-row {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: clamp(12px, 1.1vw, 20px);
  align-items: stretch;
}
.bottom-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: clamp(12px, 1.1vw, 20px);
}
.scr-empty {
  display: grid;
  place-items: center;
  min-height: 120px;
  color: rgba(196, 210, 240, 0.45);
  font-size: 13px;
}

/* 趋势图容器：覆盖 TrendChart 的浅色文字，适配深色大屏 */
.mid-row :deep(.chart) {
  color: rgba(219, 230, 255, 0.8);
}
.mid-row :deep(.chart .total),
.mid-row :deep(.chart .peak) {
  color: rgba(196, 210, 240, 0.72);
}
.mid-row :deep(.chart .grid) {
  stroke: rgba(148, 163, 184, 0.22);
}
.mid-row :deep(.chart .line) {
  stroke: #7aa2ff;
}

/* 违规类型分布 */
.dist-list {
  display: flex;
  flex-direction: column;
  gap: clamp(9px, 0.9vh, 16px);
  margin-top: 16px;
}
.dist-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 5px;
  font-size: clamp(11px, 0.78vw, 14px);
}
.dist-label {
  color: rgba(219, 230, 255, 0.82);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dist-count {
  color: #f87171;
  font-weight: 600;
}
.dist-track {
  height: 8px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.16);
  overflow: hidden;
}
.dist-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #f87171, #ef4444);
  transition: width 0.5s cubic-bezier(0.22, 1, 0.36, 1);
}

/* Top 排名 */
.rank-list {
  display: flex;
  flex-direction: column;
  gap: clamp(9px, 0.9vh, 15px);
  margin-top: 16px;
}
.rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: clamp(11.5px, 0.8vw, 14px);
}
.rank-index {
  flex: none;
  width: 22px;
  color: rgba(122, 162, 255, 0.75);
  font-weight: 600;
}
.rank-name {
  flex: 0 0 30%;
  min-width: 0;
  color: rgba(219, 230, 255, 0.86);
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rank-track {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.16);
  overflow: hidden;
}
.rank-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #3b6ef6, #7aa2ff);
  transition: width 0.5s cubic-bezier(0.22, 1, 0.36, 1);
}
.rank-fill.team {
  background: linear-gradient(90deg, #16a34a, #4ade80);
}
.rank-count {
  flex: none;
  width: 52px;
  text-align: right;
  color: #e7eeff;
  font-weight: 600;
}

@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .mid-row,
  .bottom-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
