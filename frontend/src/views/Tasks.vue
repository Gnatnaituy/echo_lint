<template>
  <div class="page stack">
    <!-- 概览 / 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">{{ t('tasks.title') }}</div>
          <span class="chip info">{{ t('common.total', { total }) }}</span>
          <el-tooltip v-if="backgroundError" :content="backgroundError" placement="bottom">
            <span class="chip warn">{{ t('tasks.refreshFailed') }}</span>
          </el-tooltip>
        </div>
        <div class="actions">
          <SegmentedControl
            v-model="statusFilter"
            :options="statusOptions"
            :aria-label="t('tasks.allStatuses')"
            @change="applyFilters"
          />
          <el-input
            v-model="recordingIdFilter"
            :placeholder="t('tasks.recordingIdPlaceholder')"
            clearable
            maxlength="18"
            class="id-input"
            @input="onRecordingIdInput"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" :icon="Search" :loading="loading" @click="applyFilters">
            {{ t('tasks.search') }}
          </el-button>
          <el-button link @click="resetFilters">{{ t('common.reset') }}</el-button>
        </div>
      </div>
      <div class="toolbar-foot">
        <div class="auto-refresh">
          <el-switch v-model="autoRefresh" size="small" @change="onAutoRefreshChange" />
          <span class="tiny dim">{{ t('tasks.autoRefresh', { seconds: REFRESH_SECONDS }) }}</span>
          <span v-if="lastLoadedAt" class="tiny dim">
            · {{ t('tasks.lastUpdated', { time: lastLoadedAt }) }}
          </span>
        </div>
        <div class="tiny dim queue-tip">{{ t('tasks.queueTip') }}</div>
      </div>
    </section>

    <!-- 任务列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load()">{{ t('common.retry') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="rows">
        <el-table-column :label="t('tasks.recording')" min-width="172">
          <template #default="{ row }">
            <div class="rec-cell">
              <div class="file-icon"><el-icon><Microphone /></el-icon></div>
              <div class="rec-text">
                <div class="strong ellipsis" :title="row.fileName">
                  {{
                    row.fileName ||
                    (row.recordingId != null
                      ? t('tasks.recordingNumber', { id: row.recordingId })
                      : '—')
                  }}
                </div>
                <div class="tiny dim ellipsis">
                  <span class="mono">{{ t('tasks.taskNumber', { id: row.id ?? '—' }) }}</span>
                  <span class="sep">·</span>
                  <span class="mono">
                    {{ t('tasks.recordingNumber', { id: row.recordingId ?? '—' }) }}
                  </span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.status')" width="100" align="center">
          <template #default="{ row }">
            <span class="chip" :class="taskStatusMeta(row.status).type">
              {{ taskStatusMeta(row.status).label }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('tasks.attempts')" width="94" align="center">
          <template #default="{ row }">
            <span class="num" :class="{ 'attempt-hot': row.attempts > 0 }">{{ taskAttemptsText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('tasks.nextAttempt')" width="176">
          <template #default="{ row }">
            <span
              v-if="row.nextAttemptAt"
              class="num"
              :title="formatRelative(row.nextAttemptAt)"
            >{{ formatTime(row.nextAttemptAt) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('tasks.lastError')" min-width="150">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.lastError"
              :content="row.lastError"
              placement="top"
              :show-after="300"
              :popper-style="{ maxWidth: '520px', wordBreak: 'break-all' }"
            >
              <span class="err-text">{{ row.lastError }}</span>
            </el-tooltip>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.updatedAt')" width="176">
          <template #default="{ row }">
            <span v-if="row.updatedAt" class="num dim" :title="formatRelative(row.updatedAt)">
              {{ formatTime(row.updatedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column v-if="hasRetryable" :label="t('common.actions')" width="96" align="right" fixed="right">
          <template #default="{ row }">
            <el-popconfirm
              v-if="isTaskRetryable(row.status) && row.id != null"
              :title="t('tasks.retryConfirm')"
              width="250"
              @confirm="retry(row)"
            >
              <template #reference>
                <el-button size="small" link type="primary" :loading="retryingId === row.id">
                  {{ t('tasks.retry') }}
                </el-button>
              </template>
            </el-popconfirm>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Operation /></el-icon>
            <div class="empty-title">
              {{ hasFilters ? t('tasks.emptyFilteredTitle') : t('tasks.emptyTitle') }}
            </div>
            <div class="tiny dim">
              {{ hasFilters ? t('tasks.emptyFilteredHint') : t('tasks.emptyHint') }}
            </div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page + 1"
          @current-change="(p) => { page = p - 1; load() }"
        />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import api from '../api'
import SegmentedControl from '../components/SegmentedControl.vue'
import {
  formatRelative,
  formatTime,
  isTaskRetryable,
  taskStatusMeta,
  TASK_STATUS_OPTIONS
} from '../constants'
import { useI18n } from '../i18n'
import { taskAttemptsText } from '../utils/tasks'

const { t } = useI18n()

const REFRESH_SECONDS = 5
const pageSize = 20

const rows = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const backgroundError = ref('')
const statusFilter = ref('')
const recordingIdFilter = ref('')
const autoRefresh = ref(true)
const retryingId = ref(null)
const lastLoadedAt = ref('')

/* 枚举型过滤统一用分段控件：全部 / 待处理 / 执行中 / 成功 / 失败 / 死信 */
const statusOptions = computed(() => [
  { value: '', label: t('tasks.allStatuses') },
  ...TASK_STATUS_OPTIONS
])

const hasFilters = computed(
  () => !!(statusFilter.value || recordingIdFilter.value.trim())
)

/** 当前页有可重试任务时才渲染操作列（成功任务全为「—」，白占一列还把更新时间挤出可视区） */
const hasRetryable = computed(() =>
  rows.value.some((row) => isTaskRetryable(row.status) && row.id != null)
)

/** 403 / 404 给出可读降级文案（列表接口 silent，不弹全局错误） */
function taskErrorMessage(err, fallback = t('tasks.loadFailed')) {
  const status = err?.status
  if (status === 403) return t('tasks.forbidden')
  if (status === 404) return t('tasks.notAvailable')
  return err?.message || fallback
}

function nowText() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function currentParams() {
  return {
    status: statusFilter.value || undefined,
    recordingId: recordingIdFilter.value.trim() || undefined,
    page: page.value,
    size: pageSize
  }
}

// 同一时刻只允许一个列表请求；用户操作（翻页/查询）在请求中被记为 pending，结束后立即补拉
let inflight = false
let pendingLoad = null

/**
 * @param {{background?: boolean}} options background=true 时静默刷新：不显示 loading、失败保留旧数据
 */
async function load(options = {}) {
  const background = options.background === true
  if (inflight) {
    if (!background) {
      loading.value = true
      pendingLoad = { background: false }
    }
    return
  }
  inflight = true
  if (!background) {
    loading.value = true
    error.value = ''
  }
  try {
    const data = await api.listTasks(currentParams())
    rows.value = data.items
    total.value = data.total
    error.value = ''
    backgroundError.value = ''
    lastLoadedAt.value = nowText()
  } catch (e) {
    if (background) {
      // 自动刷新失败：保留已加载数据，仅在头部提示，避免每 5 秒清空列表
      backgroundError.value = taskErrorMessage(e, t('tasks.backgroundFailed'))
    } else {
      rows.value = []
      total.value = 0
      error.value = taskErrorMessage(e)
    }
  } finally {
    inflight = false
    if (!background) loading.value = false
    if (pendingLoad !== null) {
      const next = pendingLoad
      pendingLoad = null
      load(next)
    }
  }
}

function applyFilters() {
  page.value = 0
  load()
}

/** 录音 ID 只允许数字，避免后端 Long 参数类型错误（400） */
function onRecordingIdInput(value) {
  const digits = String(value ?? '').replace(/\D/g, '')
  if (digits !== recordingIdFilter.value) recordingIdFilter.value = digits
}

function onAutoRefreshChange(enabled) {
  if (enabled && !inflight) load({ background: true })
}

function resetFilters() {
  statusFilter.value = ''
  recordingIdFilter.value = ''
  page.value = 0
  load()
}

async function retry(row) {
  if (retryingId.value != null) return
  retryingId.value = row.id
  try {
    await api.retryTask(row.id)
    ElMessage.success(t('tasks.retried', { id: row.id }))
    await load({ background: true })
  } catch (e) {
    // 请求层已 silent：409（非失败任务）/403/404 等在此统一提示
    ElMessage.error(taskErrorMessage(e, t('tasks.retryFailed')))
  } finally {
    retryingId.value = null
  }
}

// 5s 自动刷新：页面不可见 / 正在手动加载 / 正在重试时跳过，避免无效请求与列表跳动
let timer = null

function tick() {
  if (!autoRefresh.value || document.hidden || inflight || retryingId.value != null) return
  load({ background: true })
}

function onVisibilityChange() {
  if (!document.hidden && autoRefresh.value && !inflight) load({ background: true })
}

onMounted(() => {
  load()
  timer = setInterval(tick, REFRESH_SECONDS * 1000)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  clearInterval(timer)
  timer = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.id-input {
  width: 170px;
}
.toolbar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
  flex-wrap: wrap;
  padding: 0 18px 14px;
}
.auto-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  white-space: nowrap;
}
.queue-tip {
  flex: 1 1 380px;
  min-width: 0;
  text-align: right;
  line-height: 1.6;
}
/* 窄屏时说明文案独占一行，避免与自动刷新状态互相挤压 */
@media (max-width: 1360px) {
  .queue-tip {
    flex-basis: 100%;
    text-align: left;
  }
}

/* 表格列 */
.rec-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.rec-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.file-icon {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sep {
  margin: 0 5px;
}
.attempt-hot {
  color: #b06a06;
  font-weight: 600;
}
.err-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--danger);
  font-size: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}

/* 与用户管理页一致的错误横幅 */
.error-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 14px 18px 0;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
}
</style>
