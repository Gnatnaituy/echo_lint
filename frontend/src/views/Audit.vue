<template>
  <div class="page stack">
    <!-- 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">操作审计</div>
          <span class="tiny dim">审计记录当前登录人与请求 IP；写入失败不影响主流程</span>
        </div>
        <div class="actions">
          <el-input
            v-model="filters.actor"
            placeholder="操作人"
            clearable
            class="actor-search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select
            v-model="filters.action"
            placeholder="全部动作"
            clearable
            class="action-select"
            @change="reload"
          >
            <el-option
              v-for="opt in AUDIT_ACTION_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-button :icon="Refresh" circle @click="reload" aria-label="刷新" title="刷新" />
          <el-button :icon="Search" @click="reload">查询</el-button>
        </div>
      </div>
      <div class="hint tiny dim">按时间倒序分页；点击任意行查看完整详情。</div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">重试</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="() => 'row-clickable'"
        @row-click="openDetail"
      >
        <el-table-column label="时间" width="120">
          <template #default="{ row }">
            <span class="num" :title="formatTime(row.createdAt)">
              {{ formatSmartTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="112">
          <template #default="{ row }">
            <span class="mono">{{ row.actor || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="156">
          <template #default="{ row }">
            <div class="action-cell">
              <span class="chip" :class="actionChip(row.action)">
                {{ auditActionLabel(row.action) }}
              </span>
              <span class="action-code mono" :title="row.action || ''">{{ row.action || '—' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="140">
          <template #default="{ row }">
            <span v-if="row.targetType || row.targetId">
              {{ row.targetType || '—' }}
              <span v-if="row.targetId != null" class="mono dim">#{{ row.targetId }}</span>
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ dim: !row.detail }">{{ row.detail || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="112">
          <template #default="{ row }">
            <span class="mono tiny dim">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><List /></el-icon>
            <div class="empty-title">暂无审计记录</div>
            <div class="tiny dim">登录、用户管理、录音处理、复核与配置变更会自动写入审计</div>
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

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" size="520px" :with-header="false" append-to-body>
      <template v-if="detail">
        <div class="drawer-head">
          <div class="section-title">审计详情</div>
          <el-button :icon="Close" circle size="small" @click="detailVisible = false" aria-label="关闭" title="关闭" />
        </div>

        <el-descriptions :column="1" border class="detail-desc">
          <el-descriptions-item label="时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">
            <span class="mono">{{ detail.actor || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="动作">
            <span class="chip" :class="actionChip(detail.action)">
              {{ auditActionLabel(detail.action) }}
            </span>
            <span class="mono tiny dim ml-8">{{ detail.action || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="对象">
            <span>{{ detail.targetType || '—' }}</span>
            <span v-if="detail.targetId != null" class="mono dim ml-8">#{{ detail.targetId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="IP">
            <span class="mono">{{ detail.ip || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="block-title">附加详情</div>
        <pre v-if="detail.detail" class="detail-pre">{{ prettyDetail(detail.detail) }}</pre>
        <div v-else class="tiny dim">无附加详情</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Search, Refresh, Close } from '@element-plus/icons-vue'
import api from '../api'
import {
  AUDIT_ACTION_OPTIONS,
  auditActionLabel,
  auditActionMeta,
  formatSmartTime,
  formatTime
} from '../constants'

const filters = reactive({ actor: '', action: '' })
const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 15
const loading = ref(false)
const error = ref('')

const detailVisible = ref(false)
const detail = ref(null)

const CHIP_BY_TYPE = {
  primary: 'brand',
  success: 'ok',
  warning: 'warn',
  danger: 'danger',
  info: 'info'
}
const actionChip = (action) => CHIP_BY_TYPE[auditActionMeta(action).type] || 'info'

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listAuditLogs({
      page: page.value,
      size: pageSize,
      actor: filters.actor.trim() || undefined,
      action: filters.action || undefined
    })
    rows.value = data?.content || []
    total.value = data?.totalElements || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e?.message || '审计日志加载失败'
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 0
  load()
}

function openDetail(row) {
  if (!row) return
  detail.value = row
  detailVisible.value = true
}

/** detail 可能是 JSON 字符串：格式化便于阅读，解析失败原样展示 */
function prettyDetail(raw) {
  if (typeof raw !== 'string') return String(raw)
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.actions :deep(.el-input),
.actions :deep(.el-select) {
  flex: none;
}
.actor-search {
  width: 170px;
}
.action-select {
  width: 150px;
}
.hint {
  padding: 0 18px 14px;
  line-height: 1.7;
}

:deep(.row-clickable) {
  cursor: pointer;
}
.action-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
/* 英文 action code 弱化为小号 dim mono：保留可核对信息，不与中文标签抢视觉 */
.action-code {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  letter-spacing: 0.01em;
  color: var(--ink-400);
}
.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  margin: 8px 0 4px;
  font-weight: 600;
  color: var(--ink-600);
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}

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

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.detail-desc {
  margin-bottom: 18px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 10px;
}
.detail-pre {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-600);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 420px;
  overflow: auto;
}
.ml-8 {
  margin-left: 7px;
}
</style>
