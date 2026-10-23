<template>
  <div class="page stack">
    <!-- 筛选 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('costs.title') }}</div>
        <span class="tiny dim">{{ t('costs.subtitle') }}</span>
      </div>
      <div class="panel-body">
        <div class="filter-row">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            unlink-panels
            :range-separator="t('costs.rangeSeparator')"
            :start-placeholder="t('common.startDate')"
            :end-placeholder="t('common.endDate')"
            :shortcuts="dateShortcuts"
            class="date-range"
            @change="load"
          />
          <div class="filter-actions">
            <el-button type="primary" :icon="Search" :loading="loading" @click="load">{{ t('costs.query') }}</el-button>
            <el-button link @click="resetFilters">{{ t('common.reset') }}</el-button>
          </div>
        </div>
        <div class="hint tiny dim">
          {{ t('costs.hint') }}
        </div>
      </div>
    </section>

    <!-- 汇总卡 -->
    <div v-loading="loading" class="kpi-grid">
      <StatCard
        :label="t('costs.kpiRequests')"
        :value="stats ? formatInt(stats.requests) : '—'"
        icon="DataLine"
        tone="brand"
        :sub="rangeText"
      />
      <StatCard
        :label="t('costs.kpiInputTokens')"
        :value="stats ? formatInt(stats.inputTokens) : '—'"
        icon="Download"
        tone="info"
        sub="prompt tokens"
      />
      <StatCard
        :label="t('costs.kpiOutputTokens')"
        :value="stats ? formatInt(stats.outputTokens) : '—'"
        icon="Upload"
        tone="warn"
        sub="completion tokens"
      />
      <StatCard
        :label="t('costs.kpiEstimatedCost')"
        :value="stats ? formatUsd(stats.costUsd) : '—'"
        icon="Money"
        tone="brand"
        :sub="t('costs.costSnapshotSub')"
      />
    </div>

    <!-- 加载失败：允许原地重试 -->
    <section v-if="error" class="panel">
      <div class="error-block">
        <div class="error-icon"><el-icon :size="17"><WarningFilled /></el-icon></div>
        <div class="error-text">
          <div class="strong">{{ t('costs.statsLoadFailed') }}</div>
          <div class="tiny dim">{{ error }}</div>
        </div>
        <el-button size="small" :icon="Refresh" @click="load">{{ t('common.retry') }}</el-button>
      </div>
    </section>

    <!-- 按日 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('costs.byDay') }}</div>
        <span class="tiny dim">{{ t('costs.byDayNote') }}</span>
      </div>
      <el-table v-loading="loading" :data="byDayRows" size="small">
        <el-table-column :label="t('costs.colDate')" min-width="150">
          <template #default="{ row }">
            <span class="num strong">{{ row.date }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('costs.colRequests')" width="110" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.requests) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('costs.colInputTokens')" width="140" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.inputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('costs.colOutputTokens')" width="140" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.outputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('costs.colCost')" width="130" align="right">
          <template #default="{ row }">
            <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="32" class="empty-icon"><DataLine /></el-icon>
            <div class="empty-title">{{ error ? t('costs.statsUnavailable') : t('costs.noDailyData') }}</div>
            <div class="tiny dim">
              {{ error ? t('costs.retryHint') : t('costs.noDailyHint') }}
            </div>
          </div>
        </template>
      </el-table>
    </section>

    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('costs.byProvider') }}</div>
          <span class="tiny dim">{{ t('costs.costHighToLow') }}</span>
        </div>
        <el-table v-loading="loading" :data="byProviderRows" size="small">
          <el-table-column :label="t('costs.colProvider')" min-width="148">
            <template #default="{ row }">
              <div class="cell-name">
                <span class="strong ellipsis" :title="row.label">{{ row.label || '—' }}</span>
                <span v-if="row.key && row.key !== row.label" class="mono tiny dim">{{ row.key }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colRequests')" width="104" align="right">
            <template #default="{ row }">
              <span class="num">{{ formatInt(row.requests) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colTokensInOut')" width="140" align="right">
            <template #default="{ row }">
              <span class="num tiny">{{ formatInt(row.inputTokens) }} / {{ formatInt(row.outputTokens) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colCost')" width="100" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <el-icon :size="30" class="empty-icon"><Box /></el-icon>
              <div class="empty-title">{{ error ? t('costs.statsUnavailable') : t('costs.noProviderData') }}</div>
              <div class="tiny dim">{{ error ? t('costs.retryHint') : t('costs.noProviderHint') }}</div>
            </div>
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('costs.byModel') }}</div>
          <span class="tiny dim">{{ t('costs.costHighToLow') }}</span>
        </div>
        <el-table v-loading="loading" :data="byModelRows" size="small">
          <el-table-column :label="t('costs.colModel')" min-width="156">
            <template #default="{ row }">
              <div class="cell-name">
                <span class="mono ellipsis" :title="row.model">{{ row.model || '—' }}</span>
                <span v-if="row.providerLabel" class="tiny dim">{{ row.providerLabel }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colRequests')" width="104" align="right">
            <template #default="{ row }">
              <span class="num">{{ formatInt(row.requests) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colTokensInOut')" width="140" align="right">
            <template #default="{ row }">
              <span class="num tiny">{{ formatInt(row.inputTokens) }} / {{ formatInt(row.outputTokens) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('costs.colCost')" width="100" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <el-icon :size="30" class="empty-icon"><Cpu /></el-icon>
              <div class="empty-title">{{ error ? t('costs.statsUnavailable') : t('costs.noModelData') }}</div>
              <div class="tiny dim">{{ error ? t('costs.retryHint') : t('costs.noModelHint') }}</div>
            </div>
          </template>
        </el-table>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import { useI18n } from '../i18n'

const { t } = useI18n()

/* ---------- 日期范围：默认最近 30 天 ---------- */

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

function defaultRange() {
  return [toDateParam(dayOffset(-29)), toDateParam(new Date())]
}

const dateRange = ref(defaultRange())

const dateShortcuts = computed(() => [
  { text: t('costs.last7Days'), value: () => [dayOffset(-6), dayOffset(0)] },
  { text: t('costs.last30Days'), value: () => [dayOffset(-29), dayOffset(0)] },
  { text: t('costs.last90Days'), value: () => [dayOffset(-89), dayOffset(0)] }
])

const statsParams = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  return { from: from || undefined, to: to || undefined }
})

const rangeText = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  if (!from && !to) return t('costs.allTime')
  return `${from || t('costs.rangeEarliest')} ~ ${to || t('costs.rangeUntilNow')}`
})

/* ---------- 统计 ---------- */

const stats = ref(null)
const loading = ref(false)
const error = ref('')

const byDayRows = computed(() => (error.value ? [] : stats.value?.byDay || []))
const byProviderRows = computed(() => (error.value ? [] : stats.value?.byProvider || []))
const byModelRows = computed(() => (error.value ? [] : stats.value?.byModel || []))

/** 千分位整数：null / 缺失显示占位，0 照常显示 0 */
function formatInt(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  if (num === 0) return '0'
  return String(Math.round(num)).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 成本展示：0 直接显示 $0；小额（< $0.01）保留 6 位小数，其余 4 位；null 用占位 */
function formatUsd(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  if (num <= 0) return '$0'
  return `$${num.toFixed(num < 0.01 ? 6 : 4)}`
}

/** 403 / 404 给出可读降级文案（接口未上线时不弹全局错误） */
function usageErrorMessage(err) {
  const status = err?.status
  if (status === 403) return t('costs.errorForbidden')
  if (status === 404) return t('costs.errorEndpointMissing')
  return err?.message || t('costs.errorFallback')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await api.aiUsage(statsParams.value)
  } catch (e) {
    stats.value = null
    error.value = usageErrorMessage(e)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  dateRange.value = defaultRange()
  load()
}

onMounted(load)
</script>

<style scoped>
/* 筛选 */
.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* .el-input__wrapper 默认 flex-grow:1，日期框不再抢宽（根节点无 scoped 属性，用 :deep） */
.filter-row :deep(.date-range) {
  flex: none;
  width: 300px;
}
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
.hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

/* 汇总卡：1280 / 1440 都是一行 4 张，窄屏再自然换行 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(196px, 1fr));
  gap: 14px;
  border-radius: var(--r-lg);
}
/* 手机：2 列 KPI，避免 4 张卡竖排成一长串 */
@media (max-width: 720px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}

/* 双栏表格 */
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1200px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
}
.cell-name {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  line-height: 1.5;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* 错误态（与报表页一致） */
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
