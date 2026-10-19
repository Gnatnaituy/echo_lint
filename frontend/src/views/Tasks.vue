<template>
  <div class="page stack">
    <!-- 概览 / 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">任务队列</div>
          <span class="chip info">共 {{ total }} 条</span>
          <span v-if="statusFilter" class="chip" :class="taskStatusMeta(statusFilter).type">
            {{ taskStatusMeta(statusFilter).label }}
          </span>
          <el-tooltip v-if="backgroundError" :content="backgroundError" placement="bottom">
            <span class="chip warn">刷新失败</span>
          </el-tooltip>
        </div>
        <div class="actions">
          <el-select
            v-model="statusFilter"
            placeholder="全部状态"
            clearable
            class="status-select"
            @change="applyFilters"
          >
            <el-option
              v-for="opt in TASK_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input
            v-model="recordingIdFilter"
            placeholder="录音 ID"
            clearable
            maxlength="18"
            class="id-input"
            @input="onRecordingIdInput"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" :icon="Search" :loading="loading" @click="applyFilters">查询</el-button>
          <el-button link @click="resetFilters">重置</el-button>
        </div>
      </div>
      <div class="toolbar-foot">
        <div class="auto-refresh">
          <el-switch v-model="autoRefresh" size="small" @change="onAutoRefreshChange" />
          <span class="tiny dim">每 {{ REFRESH_SECONDS }} 秒自动刷新</span>
          <span v-if="lastLoadedAt" class="tiny dim">· 最后更新 {{ lastLoadedAt }}</span>
        </div>
        <div class="tiny dim queue-tip">
          上传 / 重试录音会入队执行；失败按 30s / 120s / 300s 退避重试，
          达到最大次数进入死信（最终失败），可手动重试。
        </div>
      </div>
    </section>

    <!-- 任务列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load()">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="rows">
        <el-table-column label="录音" min-width="240">
          <template #default="{ row }">
            <div class="rec-cell">
              <div class="file-icon"><el-icon><Microphone /></el-icon></div>
              <div class="min-w-0">
                <div class="strong ellipsis" :title="row.fileName">
                  {{ row.fileName || (row.recordingId != null ? `录音 #${row.recordingId}` : '—') }}
                </div>
                <div class="tiny dim">
                  <span class="mono">任务 #{{ row.id ?? '—' }}</span>
                  <span class="sep">·</span>
                  <span class="mono">录音 #{{ row.recordingId ?? '—' }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="112" align="center">
          <template #default="{ row }">
            <span class="chip" :class="taskStatusMeta(row.status).type">
              {{ taskStatusMeta(row.status).label }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="尝试" width="96" align="center">
          <template #default="{ row }">
            <span class="num" :class="{ 'attempt-hot': row.attempts > 0 }">{{ taskAttemptsText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="下次尝试" width="170">
          <template #default="{ row }">
            <span
              v-if="row.nextAttemptAt"
              class="num"
              :title="formatRelative(row.nextAttemptAt)"
            >{{ formatTime(row.nextAttemptAt) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最近错误" min-width="260">
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

        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span v-if="row.updatedAt" class="num dim" :title="formatRelative(row.updatedAt)">
              {{ formatTime(row.updatedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="96" align="right">
          <template #default="{ row }">
            <el-popconfirm
              v-if="isTaskRetryable(row.status) && row.id != null"
              title="重试会重新排队并将尝试次数归零，确认重试？"
              width="250"
              @confirm="retry(row)"
            >
              <template #reference>
                <el-button size="small" link type="primary" :loading="retryingId === row.id">
                  重试
                </el-button>
              </template>
            </el-popconfirm>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Operation /></el-icon>
            <div class="empty-title">{{ hasFilters ? '没有匹配的任务' : '暂无任务' }}</div>
            <div class="tiny dim">
              {{ hasFilters
                ? '试试清空状态或录音 ID 筛选'
                : '上传或重试录音后，流水线任务会在这里排队执行' }}
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
import {
  formatRelative,
  formatTime,
  isTaskRetryable,
  taskStatusMeta,
  TASK_STATUS_OPTIONS
} from '../constants'
import { taskAttemptsText } from '../utils/tasks'

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

const hasFilters = computed(
  () => !!(statusFilter.value || recordingIdFilter.value.trim())
)

/** 403 / 404 给出可读降级文案（列表接口 silent，不弹全局错误） */
function taskErrorMessage(err, fallback = '任务列表加载失败，请稍后重试') {
  const status = err?.status
  if (status === 403) return '当前账号无权限访问任务队列（需要 ADMIN）'
  if (status === 404) return '后端尚未提供任务队列接口（HTTP 404），请确认后端版本'
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
      backgroundError.value = taskErrorMessage(e, '自动刷新失败，请检查后端服务')
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
    ElMessage.success(`任务 #${row.id} 已重新排队`)
    await load({ background: true })
  } catch (e) {
    // 请求层已 silent：409（非失败任务）/403/404 等在此统一提示
    ElMessage.error(taskErrorMessage(e, '重试失败，请稍后重试'))
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
.status-select {
  width: 138px;
}
.id-input {
  width: 170px;
}
.toolbar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 0 18px 14px;
}
.auto-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
}
.queue-tip {
  line-height: 1.6;
}

/* 表格列 */
.rec-cell {
  display: flex;
  align-items: center;
  gap: 10px;
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
.min-w-0 {
  min-width: 0;
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
