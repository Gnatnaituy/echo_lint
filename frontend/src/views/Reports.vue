<template>
  <div class="page stack">
    <!-- 筛选与导出 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('reports.title') }}</div>
        <div class="export-actions">
          <el-button
            v-for="job in exportJobs"
            :key="job.key"
            size="small"
            :icon="Download"
            :loading="exporting === job.key"
            :disabled="!!exporting && exporting !== job.key"
            @click="exportCsv(job)"
          >{{ job.label }}</el-button>
        </div>
      </div>
      <div class="panel-body">
        <div class="filter-row">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            unlink-panels
            :range-separator="t('reports.rangeSeparator')"
            :start-placeholder="t('common.startDate')"
            :end-placeholder="t('common.endDate')"
            :shortcuts="dateShortcuts"
            class="date-range"
            @change="loadStats"
          />
          <el-input
            v-model="agentFilter"
            :placeholder="t('reports.filterAgent')"
            clearable
            class="filter-input"
            @keyup.enter="loadStats"
            @clear="loadStats"
          />
          <el-input
            v-model="teamFilter"
            :placeholder="t('reports.filterTeam')"
            clearable
            class="filter-input"
            @keyup.enter="loadStats"
            @clear="loadStats"
          />
          <el-select
            v-model="directionFilter"
            :placeholder="t('reports.allDirections')"
            clearable
            class="direction-select"
            @change="loadStats"
          >
            <el-option
              v-for="opt in DIRECTION_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="filter-actions">
            <el-button type="primary" :icon="Search" :loading="statsLoading" @click="loadStats">
              {{ t('reports.query') }}
            </el-button>
            <el-button link @click="resetFilters">{{ t('common.reset') }}</el-button>
          </div>
        </div>
        <div class="hint tiny">
          {{ t('reports.hintPrefix') }}<span class="strong">{{ t('reports.hintDateRange') }}</span>{{ t('reports.hintMiddle') }}<span class="strong">{{ t('reports.hintCsvExport') }}</span>{{ t('reports.hintSuffix') }}
        </div>
      </div>
    </section>

    <!-- 汇总卡 -->
    <div v-loading="statsLoading" class="kpi-grid">
      <StatCard
        :label="t('reports.kpiTotal')"
        :value="stats ? totalCount : '—'"
        icon="Files"
        tone="brand"
        :sub="rangeText"
      />
      <StatCard
        :label="t('reports.kpiConfirmed')"
        :value="stats ? confirmed : '—'"
        icon="WarningFilled"
        tone="danger"
        :sub="t('reports.violationRateSub', { rate: violationRate })"
      />
      <StatCard
        :label="t('reports.kpiNeedsReview')"
        :value="stats ? needsReview : '—'"
        icon="DocumentChecked"
        tone="warn"
        :sub="t('reports.needsReviewSub')"
      />
      <StatCard
        :label="t('reports.kpiCompliant')"
        :value="stats ? compliant : '—'"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="t('reports.falsePositiveSub', { count: falsePositive })"
      />
      <StatCard
        :label="t('reports.kpiSampled')"
        :value="stats ? sampledCount : '—'"
        icon="Aim"
        tone="info"
        :sub="sampledSub"
      />
    </div>

    <!-- 加载失败：允许原地重试，不打断导出 -->
    <section v-if="error" class="panel">
      <div class="error-block">
        <div class="error-icon"><el-icon :size="17"><WarningFilled /></el-icon></div>
        <div class="error-text">
          <div class="strong">{{ t('reports.statsLoadFailed') }}</div>
          <div class="tiny dim">{{ error }}</div>
        </div>
        <el-button size="small" :icon="Refresh" @click="loadStats">{{ t('common.retry') }}</el-button>
      </div>
    </section>

    <!-- 按坐席 / 按团队 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('reports.byAgent') }}</div>
          <span class="tiny dim">{{ t('reports.sortNote') }}</span>
        </div>
        <el-table v-loading="statsLoading" :data="agentRows" size="small">
          <el-table-column :label="t('reports.colAgent')" min-width="150">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('reports.colRecordings')" width="110" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ row.count }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('reports.colShare')" min-width="150">
            <template #default="{ row }">
              <div class="bar">
                <div class="bar-fill" :style="{ width: barWidth(row.count, agentMax) }"></div>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <el-icon :size="30" class="empty-icon"><User /></el-icon>
              <div class="empty-title">{{ error ? t('reports.statsUnavailable') : t('reports.noAgentData') }}</div>
              <div class="tiny dim">
                {{ error ? t('reports.retryHint') : t('reports.noAgentHint') }}
              </div>
            </div>
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('reports.byTeam') }}</div>
          <span class="tiny dim">{{ t('reports.sortNote') }}</span>
        </div>
        <el-table v-loading="statsLoading" :data="teamRows" size="small">
          <el-table-column :label="t('reports.colTeam')" min-width="150">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('reports.colRecordings')" width="110" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ row.count }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('reports.colShare')" min-width="150">
            <template #default="{ row }">
              <div class="bar">
                <div class="bar-fill" :style="{ width: barWidth(row.count, teamMax) }"></div>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <el-icon :size="30" class="empty-icon"><OfficeBuilding /></el-icon>
              <div class="empty-title">{{ error ? t('reports.statsUnavailable') : t('reports.noTeamData') }}</div>
              <div class="tiny dim">
                {{ error ? t('reports.retryHint') : t('reports.noTeamHint') }}
              </div>
            </div>
          </template>
        </el-table>
      </section>
    </div>

    <!-- 按违规类型 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('reports.byViolationType') }}</div>
        <span class="tiny dim">{{ t('reports.byViolationTypeNote') }}</span>
      </div>
      <el-table v-loading="statsLoading" :data="violationRows" size="small">
        <el-table-column :label="t('reports.colViolationType')" min-width="170">
          <template #default="{ row }">
            <span class="chip warn">{{ row.label }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('reports.colCount')" width="86" align="right">
          <template #default="{ row }">
            <span class="num strong">{{ row.count }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('reports.colShareOfConfirmed')" min-width="260">
          <template #default="{ row }">
            <div class="bar-row">
              <div class="bar">
                <div class="bar-fill danger" :style="{ width: barWidth(row.count, violationMax) }"></div>
              </div>
              <span class="num tiny dim">{{ pct(row.count, confirmed) }}%</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="30" class="empty-icon"><WarningFilled /></el-icon>
            <div class="empty-title">{{ error ? t('reports.statsUnavailable') : t('reports.noViolationData') }}</div>
            <div class="tiny dim">
              {{ error ? t('reports.retryHint') : t('reports.noViolationHint') }}
            </div>
          </div>
        </template>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import { DIRECTION_OPTIONS, VIOLATION_TYPES } from '../constants'
import { useI18n } from '../i18n'
import { saveBlob } from '../utils/download'

const { t } = useI18n()

/* ---------- 筛选 ---------- */

const dateRange = ref(defaultRange())
const agentFilter = ref('')
const teamFilter = ref('')
const directionFilter = ref('')

function defaultRange() {
  const today = new Date()
  const start = new Date()
  start.setDate(today.getDate() - 29)
  return [toDateParam(start), toDateParam(today)]
}

/** 日期参数统一 yyyy-MM-dd（快捷选项可能给 Date） */
function toDateParam(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(/\//g, '-'))
  if (Number.isNaN(d.getTime())) return typeof value === 'string' ? value : ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function dayOffset(days) {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() + days)
  return d
}

const dateShortcuts = computed(() => [
  { text: t('reports.last7Days'), value: () => [dayOffset(-6), dayOffset(0)] },
  { text: t('reports.last30Days'), value: () => [dayOffset(-29), dayOffset(0)] },
  { text: t('reports.last90Days'), value: () => [dayOffset(-89), dayOffset(0)] }
])

function resetFilters() {
  dateRange.value = defaultRange()
  agentFilter.value = ''
  teamFilter.value = ''
  directionFilter.value = ''
  loadStats()
}

/** 导出参数：与列表筛选同名，空值由 api 层剔除 */
const exportParams = computed(() => ({
  from: toDateParam(dateRange.value?.[0]),
  to: toDateParam(dateRange.value?.[1]),
  agentName: agentFilter.value.trim(),
  teamName: teamFilter.value.trim(),
  direction: directionFilter.value
}))

/* ---------- 统计 ---------- */

const stats = ref(null)
const statsLoading = ref(false)
const error = ref('')

const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const totalCount = computed(() => num(stats.value?.total))
const confirmed = computed(() =>
  stats.value ? num(stats.value.confirmed ?? stats.value.byStatus?.VIOLATION_CONFIRMED) : 0
)
const falsePositive = computed(() =>
  stats.value ? num(stats.value.falsePositive ?? stats.value.byStatus?.FALSE_POSITIVE) : 0
)
const needsReview = computed(() => num(stats.value?.byStatus?.NEEDS_REVIEW))
const compliant = computed(() => num(stats.value?.byStatus?.COMPLIANT))

/** 抽样数：后端新增 stats.sampled；旧版本缺字段时按 0 展示，不臆造 */
const sampledCount = computed(() => num(stats.value?.sampled ?? stats.value?.sampledCount))
const sampledSub = computed(() =>
  totalCount.value > 0
    ? t('reports.sampledShareSub', { percent: pct(sampledCount.value, totalCount.value) })
    : t('reports.sampledFallbackSub')
)

const violationRate = computed(() =>
  totalCount.value > 0 ? Math.round((confirmed.value / totalCount.value) * 1000) / 10 : 0
)

const rangeText = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  if (!from && !to) return t('reports.allTime')
  // 汇总卡副标题空间有限：同年份时省略年份，跨年时保留完整日期
  if (from && to && from.slice(0, 4) === to.slice(0, 4)) {
    return `${from.slice(5)} ~ ${to.slice(5)}`
  }
  return `${from || t('reports.rangeEarliest')} ~ ${to || t('reports.rangeUntilNow')}`
})

const statsParams = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  return { from: from || undefined, to: to || undefined }
})

/** topAgents/topTeams：[{name,count}] */
function normalizeTop(raw) {
  if (!Array.isArray(raw)) return []
  // 先取兜底文案，保证 computed 订阅语言变化（仅在缺名时才调用 t 会漏订阅）
  const notFilled = t('common.notFilled')
  return raw
    .map((r) => ({ name: String(r?.name || notFilled), count: num(r?.count) }))
    .filter((r) => r.count > 0)
}

/** byViolationType：后端为 [{type,label,count}]，兼容对象 map 形态 */
function normalizeViolationTypes(raw) {
  let rows = []
  // 先取枚举兜底，保证 computed 订阅语言变化（后端带 label 时也要跟随切换）
  const otherLabel = VIOLATION_TYPES.OTHER
  if (Array.isArray(raw)) {
    rows = raw.map((r) => ({
      type: r?.type || '',
      label: r?.label || VIOLATION_TYPES[r?.type] || r?.type || otherLabel,
      count: num(r?.count)
    }))
  } else if (raw && typeof raw === 'object') {
    rows = Object.entries(raw).map(([type, count]) => ({
      type,
      label: VIOLATION_TYPES[type] || type,
      count: num(count)
    }))
  }
  return rows.filter((r) => r.count > 0).sort((a, b) => b.count - a.count)
}

const agentRows = computed(() => normalizeTop(stats.value?.topAgents))
const teamRows = computed(() => normalizeTop(stats.value?.topTeams))
const violationRows = computed(() => normalizeViolationTypes(stats.value?.byViolationType))

const agentMax = computed(() => Math.max(1, ...agentRows.value.map((r) => r.count)))
const teamMax = computed(() => Math.max(1, ...teamRows.value.map((r) => r.count)))
const violationMax = computed(() => Math.max(1, ...violationRows.value.map((r) => r.count)))

/** 条宽：0 不画，其余至少 2% 以便可见 */
function barWidth(count, max) {
  if (!count) return '0%'
  return `${Math.max(2, Math.round((count / Math.max(1, max)) * 100))}%`
}

function pct(count, total) {
  if (!total) return 0
  return Math.round((count / total) * 1000) / 10
}

async function loadStats() {
  statsLoading.value = true
  error.value = ''
  try {
    stats.value = await api.statsReport(statsParams.value)
  } catch (e) {
    stats.value = null
    error.value = e?.message || t('reports.statsErrorFallback')
  } finally {
    statsLoading.value = false
  }
}

/* ---------- 导出 ---------- */

const exportJobs = computed(() => [
  {
    key: 'recordings',
    label: t('reports.exportRecordings'),
    run: (params) => api.exportRecordingsCsv(params)
  },
  {
    key: 'violations',
    label: t('reports.exportViolations'),
    run: (params) => api.exportViolationsCsv(params)
  },
  {
    // 复核记录按复核时间过滤，只接受 from/to
    key: 'reviews',
    label: t('reports.exportReviews'),
    run: (params) => api.exportReviewsCsv({ from: params.from, to: params.to })
  }
])

const exporting = ref('')

async function exportCsv(job) {
  if (exporting.value) return
  exporting.value = job.key
  try {
    const { blob, filename } = await job.run(exportParams.value)
    saveBlob(blob, filename)
    ElMessage.success(t('reports.exportStarted', { label: job.label, filename }))
  } catch {
    // 请求层 / 接口层已统一提示，这里保持按钮可用以便重试
  } finally {
    exporting.value = ''
  }
}

onMounted(loadStats)
</script>

<style scoped>
/* 筛选 */
.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* Element 的 .el-input__wrapper 默认 flex-grow:1，会把日期框拉宽把「重置」挤到下一行；
   日期选择器根节点不带父级 scoped 属性，需用 :deep 指定 */
.filter-row :deep(.date-range) {
  flex: none;
  width: 296px;
}
.filter-input {
  flex: none;
  width: 136px;
}
.direction-select {
  flex: none;
  width: 128px;
}
/* 查询 / 重置作为一组整体换行，并与右侧对齐 */
.filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  margin-left: auto;
}
.filter-actions .el-button + .el-button {
  margin-left: 0;
}
.export-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.export-actions .el-button + .el-button {
  margin-left: 0;
}
.hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

/* 汇总卡：auto-fit 让 5 张卡在 1440 / 1280 都排满一行，不留下 3+2 的空位 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(176px, 1fr));
  gap: 14px;
  border-radius: var(--r-lg);
}
/* 手机：2 列，避免 5 张卡竖排成一长串 */
@media (max-width: 720px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}

/* 表格布局 */
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
/* 两栏在 1280 笔记本宽度仍可并排（各约 470px），低于 1200 再堆叠 */
@media (max-width: 1200px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
}
.cell-ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 占比条：更明显的 track / fill 对比，统一 8px 高与全圆角 */
.bar {
  height: 8px;
  border-radius: 999px;
  background: #e7edf6;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.12);
  overflow: hidden;
  min-width: 60px;
}
.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--brand-400), var(--brand-600));
  transition: width 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}
.bar-fill.danger {
  background: linear-gradient(90deg, #f47c7c, var(--danger));
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-row .bar {
  flex: 1;
}
.bar-row .num {
  flex: none;
  min-width: 44px;
  text-align: right;
}

/* 表格空态：圆形图标底座，避免空白区域过于生硬 */
.empty-block .empty-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-300);
  background: var(--ink-50);
  border: 1px dashed var(--border-strong);
  margin-bottom: 8px;
}
.empty-block .empty-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}

/* 错误态 */
.error-block {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
}
.error-icon {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--danger-soft);
  color: #c92c2c;
}
.error-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
</style>
