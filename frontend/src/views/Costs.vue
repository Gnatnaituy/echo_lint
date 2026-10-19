<template>
  <div class="page stack">
    <!-- 筛选 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">AI 用量与成本</div>
        <span class="tiny dim">基于 AI 调用留痕统计，成本为调用时单价的快照估算</span>
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
            @change="load"
          />
          <el-button type="primary" :icon="Search" :loading="loading" @click="load">查询</el-button>
          <el-button link @click="resetFilters">重置</el-button>
        </div>
        <div class="hint tiny dim">
          数据来自语义复筛与敏感词挖掘的真实调用（含失败重试）；单价在调用发生时快照，
          之后修改供应商价格不会回溯历史成本。清空日期范围表示全部时间。
        </div>
      </div>
    </section>

    <!-- 汇总卡 -->
    <div v-loading="loading" class="kpi-grid">
      <StatCard
        label="请求次数"
        :value="stats ? formatInt(stats.requests) : '—'"
        icon="DataLine"
        tone="brand"
        :sub="rangeText"
      />
      <StatCard
        label="输入 Tokens"
        :value="stats ? formatInt(stats.inputTokens) : '—'"
        icon="Download"
        tone="info"
        sub="prompt tokens"
      />
      <StatCard
        label="输出 Tokens"
        :value="stats ? formatInt(stats.outputTokens) : '—'"
        icon="Upload"
        tone="warn"
        sub="completion tokens"
      />
      <StatCard
        label="预估成本"
        :value="stats ? formatUsd(stats.costUsd) : '—'"
        icon="Money"
        tone="danger"
        sub="按调用时单价快照估算"
      />
    </div>

    <!-- 加载失败：允许原地重试 -->
    <section v-if="error" class="panel">
      <div class="error-block">
        <div class="error-icon"><el-icon :size="17"><WarningFilled /></el-icon></div>
        <div class="error-text">
          <div class="strong">统计加载失败</div>
          <div class="tiny dim">{{ error }}</div>
        </div>
        <el-button size="small" :icon="Refresh" @click="load">重试</el-button>
      </div>
    </section>

    <!-- 按日 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">按日用量</div>
        <span class="tiny dim">按调用日期汇总</span>
      </div>
      <el-table v-loading="loading" :data="byDayRows" size="small">
        <el-table-column label="日期" min-width="150">
          <template #default="{ row }">
            <span class="num strong">{{ row.date }}</span>
          </template>
        </el-table-column>
        <el-table-column label="请求" width="110" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.requests) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="输入 Tokens" width="140" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.inputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="输出 Tokens" width="140" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatInt(row.outputTokens) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成本" width="130" align="right">
          <template #default="{ row }">
            <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="32" class="empty-icon"><DataLine /></el-icon>
            <div class="empty-title">{{ error ? '统计暂不可用' : '所选时间范围内暂无 AI 调用' }}</div>
            <div class="tiny dim">
              {{ error ? '请点击上方「重试」重新加载' : '录音进入 AI 语义复筛后，这里会按日汇总调用量与成本' }}
            </div>
          </div>
        </template>
      </el-table>
    </section>

    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">按供应商</div>
          <span class="tiny dim">成本从高到低</span>
        </div>
        <el-table v-loading="loading" :data="byProviderRows" size="small">
          <el-table-column label="供应商" min-width="170">
            <template #default="{ row }">
              <div class="cell-name">
                <span class="strong ellipsis" :title="row.label">{{ row.label || '—' }}</span>
                <span v-if="row.key && row.key !== row.label" class="mono tiny dim">{{ row.key }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="请求" width="84" align="right">
            <template #default="{ row }">
              <span class="num">{{ formatInt(row.requests) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Tokens（入 / 出）" width="150" align="right">
            <template #default="{ row }">
              <span class="num tiny">{{ formatInt(row.inputTokens) }} / {{ formatInt(row.outputTokens) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成本" width="116" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <div class="empty-title">{{ error ? '统计暂不可用' : '暂无供应商用量' }}</div>
              <div class="tiny dim">{{ error ? '请点击上方「重试」重新加载' : '启用并调用供应商后这里会按供应商汇总' }}</div>
            </div>
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">按模型</div>
          <span class="tiny dim">成本从高到低</span>
        </div>
        <el-table v-loading="loading" :data="byModelRows" size="small">
          <el-table-column label="模型" min-width="180">
            <template #default="{ row }">
              <div class="cell-name">
                <span class="mono ellipsis" :title="row.model">{{ row.model || '—' }}</span>
                <span v-if="row.providerLabel" class="tiny dim">{{ row.providerLabel }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="请求" width="84" align="right">
            <template #default="{ row }">
              <span class="num">{{ formatInt(row.requests) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Tokens（入 / 出）" width="150" align="right">
            <template #default="{ row }">
              <span class="num tiny">{{ formatInt(row.inputTokens) }} / {{ formatInt(row.outputTokens) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成本" width="116" align="right">
            <template #default="{ row }">
              <span class="num strong">{{ formatUsd(row.costUsd) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <div class="empty-title">{{ error ? '统计暂不可用' : '暂无模型用量' }}</div>
              <div class="tiny dim">{{ error ? '请点击上方「重试」重新加载' : '模型产生调用后这里会按模型汇总' }}</div>
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

const dateShortcuts = [
  { text: '最近 7 天', value: () => [dayOffset(-6), dayOffset(0)] },
  { text: '最近 30 天', value: () => [dayOffset(-29), dayOffset(0)] },
  { text: '最近 90 天', value: () => [dayOffset(-89), dayOffset(0)] }
]

const statsParams = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  return { from: from || undefined, to: to || undefined }
})

const rangeText = computed(() => {
  const from = toDateParam(dateRange.value?.[0])
  const to = toDateParam(dateRange.value?.[1])
  if (!from && !to) return '全部时间'
  return `${from || '最早'} ~ ${to || '至今'}`
})

/* ---------- 统计 ---------- */

const stats = ref(null)
const loading = ref(false)
const error = ref('')

const byDayRows = computed(() => (error.value ? [] : stats.value?.byDay || []))
const byProviderRows = computed(() => (error.value ? [] : stats.value?.byProvider || []))
const byModelRows = computed(() => (error.value ? [] : stats.value?.byModel || []))

/** 千分位整数：NaN / 负数兜底 0 */
function formatInt(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num <= 0) return '0'
  return String(Math.round(num)).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 成本展示：小额（< $0.01）保留 6 位小数，其余 4 位 */
function formatUsd(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num <= 0) return '$0.0000'
  return `$${num.toFixed(num < 0.01 ? 6 : 4)}`
}

/** 403 / 404 给出可读降级文案（接口未上线时不弹全局错误） */
function usageErrorMessage(err) {
  const status = err?.status
  if (status === 403) return '当前账号无权限查看成本看板（需要 ADMIN）'
  if (status === 404) return '后端尚未提供 AI 用量接口（HTTP 404），请确认后端版本'
  return err?.message || '统计加载失败，请稍后重试'
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
.date-range {
  width: 300px;
}
.hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

/* 汇总卡 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  border-radius: var(--r-lg);
}
@media (max-width: 1360px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 双栏表格 */
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
.cell-name {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.5;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
