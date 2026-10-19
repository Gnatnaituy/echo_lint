<template>
  <div class="page stack">
    <!-- 筛选与导出 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">报表与导出</div>
        <div class="export-actions">
          <el-button
            v-for="job in EXPORT_JOBS"
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
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="dateShortcuts"
            class="date-range"
            @change="loadStats"
          />
          <el-input
            v-model="agentFilter"
            placeholder="坐席"
            clearable
            class="filter-input"
            @keyup.enter="loadStats"
            @clear="loadStats"
          />
          <el-input
            v-model="teamFilter"
            placeholder="团队"
            clearable
            class="filter-input"
            @keyup.enter="loadStats"
            @clear="loadStats"
          />
          <el-select
            v-model="directionFilter"
            placeholder="全部方向"
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
          <el-button type="primary" :icon="Search" :loading="statsLoading" @click="loadStats">
            查询
          </el-button>
          <el-button link @click="resetFilters">重置</el-button>
        </div>
        <div class="hint tiny">
          汇总卡与明细表按<span class="strong">日期范围</span>统计（GET /stats/report）；坐席 / 团队 / 方向筛选作用于上方
          <span class="strong">CSV 导出</span>（导出为 UTF-8 BOM，Excel 可直接打开中文）。清空日期范围表示全部时间。
        </div>
      </div>
    </section>

    <!-- 汇总卡 -->
    <div v-loading="statsLoading" class="kpi-grid">
      <StatCard
        label="录音总量"
        :value="stats ? totalCount : '—'"
        icon="Files"
        tone="brand"
        :sub="rangeText"
      />
      <StatCard
        label="确认违规"
        :value="stats ? confirmed : '—'"
        icon="WarningFilled"
        tone="danger"
        :sub="`违规率 ${violationRate}%`"
      />
      <StatCard
        label="待人工复检"
        :value="stats ? needsReview : '—'"
        icon="DocumentChecked"
        tone="warn"
        sub="初筛与复筛均判违规"
      />
      <StatCard
        label="合规通过"
        :value="stats ? compliant : '—'"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="`误判放行 ${falsePositive} 条`"
      />
      <StatCard
        label="抽样数"
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
          <div class="strong">统计加载失败</div>
          <div class="tiny dim">{{ error }}</div>
        </div>
        <el-button size="small" :icon="Refresh" @click="loadStats">重试</el-button>
      </div>
    </section>

    <!-- 按坐席 / 按团队 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">按坐席</div>
          <span class="tiny dim">按录音量排序，最多 10 条</span>
        </div>
        <el-table v-loading="statsLoading" :data="agentRows" size="small">
          <el-table-column label="坐席" min-width="150">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="录音数" width="86" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ row.count }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占比" min-width="150">
            <template #default="{ row }">
              <div class="bar">
                <div class="bar-fill" :style="{ width: barWidth(row.count, agentMax) }"></div>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <div class="empty-title">{{ error ? '统计暂不可用' : '暂无坐席数据' }}</div>
              <div class="tiny dim">
                {{ error ? '请点击上方「重试」重新加载' : '上传录音时填写「坐席」后即可按坐席统计' }}
              </div>
            </div>
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">按团队</div>
          <span class="tiny dim">按录音量排序，最多 10 条</span>
        </div>
        <el-table v-loading="statsLoading" :data="teamRows" size="small">
          <el-table-column label="团队" min-width="150">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="录音数" width="86" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ row.count }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占比" min-width="150">
            <template #default="{ row }">
              <div class="bar">
                <div class="bar-fill" :style="{ width: barWidth(row.count, teamMax) }"></div>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <div class="empty-title">{{ error ? '统计暂不可用' : '暂无团队数据' }}</div>
              <div class="tiny dim">
                {{ error ? '请点击上方「重试」重新加载' : '上传录音时填写「团队」后即可按团队统计' }}
              </div>
            </div>
          </template>
        </el-table>
      </section>
    </div>

    <!-- 按违规类型 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">按违规类型</div>
        <span class="tiny dim">仅统计人工确认违规（已复核）</span>
      </div>
      <el-table v-loading="statsLoading" :data="violationRows" size="small">
        <el-table-column label="违规类型" min-width="170">
          <template #default="{ row }">
            <span class="chip warn">{{ row.label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="86" align="right">
          <template #default="{ row }">
            <span class="num strong">{{ row.count }}</span>
          </template>
        </el-table-column>
        <el-table-column label="占确认违规比例" min-width="260">
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
            <div class="empty-title">{{ error ? '统计暂不可用' : '暂无违规类型数据' }}</div>
            <div class="tiny dim">
              {{ error ? '请点击上方「重试」重新加载' : '人工复检确认违规后，这里会按违规类型汇总' }}
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
import { saveBlob } from '../utils/download'

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

const dateShortcuts = [
  { text: '最近 7 天', value: () => [dayOffset(-6), dayOffset(0)] },
  { text: '最近 30 天', value: () => [dayOffset(-29), dayOffset(0)] },
  { text: '最近 90 天', value: () => [dayOffset(-89), dayOffset(0)] }
]

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
    ? `占录音总量 ${pct(sampledCount.value, totalCount.value)}%`
    : 'DFA 未命中的抽样抽检'
)

const violationRate = computed(() =>
  totalCount.value > 0 ? Math.round((confirmed.value / totalCount.value) * 1000) / 10 : 0
)

const rangeText = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  if (!from && !to) return '全部时间'
  return `${from || '最早'} ~ ${to || '至今'}`
})

const statsParams = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  return { from: from || undefined, to: to || undefined }
})

/** topAgents/topTeams：[{name,count}] */
function normalizeTop(raw) {
  if (!Array.isArray(raw)) return []
  return raw
    .map((r) => ({ name: String(r?.name || '未填写'), count: num(r?.count) }))
    .filter((r) => r.count > 0)
}

/** byViolationType：后端为 [{type,label,count}]，兼容对象 map 形态 */
function normalizeViolationTypes(raw) {
  let rows = []
  if (Array.isArray(raw)) {
    rows = raw.map((r) => ({
      type: r?.type || '',
      label: r?.label || VIOLATION_TYPES[r?.type] || r?.type || '其他',
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
    error.value = e?.message || '请求失败，请稍后重试'
  } finally {
    statsLoading.value = false
  }
}

/* ---------- 导出 ---------- */

const EXPORT_JOBS = [
  {
    key: 'recordings',
    label: '录音清单',
    run: (params) => api.exportRecordingsCsv(params)
  },
  {
    key: 'violations',
    label: '违规明细',
    run: (params) => api.exportViolationsCsv(params)
  },
  {
    // 复核记录按复核时间过滤，只接受 from/to
    key: 'reviews',
    label: '复核记录',
    run: (params) => api.exportReviewsCsv({ from: params.from, to: params.to })
  }
]

const exporting = ref('')

async function exportCsv(job) {
  if (exporting.value) return
  exporting.value = job.key
  try {
    const { blob, filename } = await job.run(exportParams.value)
    saveBlob(blob, filename)
    ElMessage.success(`${job.label}已开始下载：${filename}`)
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
.date-range {
  width: 300px;
}
.filter-input {
  width: 140px;
}
.direction-select {
  width: 128px;
}
.export-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

/* 汇总卡 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
  border-radius: var(--r-lg);
}
@media (max-width: 1500px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1000px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 表格布局 */
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1360px) {
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

/* 占比条 */
.bar {
  height: 7px;
  border-radius: 999px;
  background: var(--ink-100);
  overflow: hidden;
  min-width: 60px;
}
.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--brand-400), var(--brand-500));
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
