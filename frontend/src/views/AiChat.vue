<template>
  <div class="page stack">
    <!-- 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">AI 对话记录</div>
          <span class="tiny dim">默认展示语义复筛对话；可切换阶段查看词挖掘 / embedding 调用</span>
        </div>
        <div class="actions">
          <el-select
            v-model="filters.stage"
            placeholder="全部阶段"
            clearable
            class="stage-select"
            @change="reload"
          >
            <el-option
              v-for="option in stageOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select
            v-model="filters.providerId"
            placeholder="全部供应商"
            clearable
            class="provider-select"
            @change="reload"
          >
            <el-option
              v-for="provider in facets.providers"
              :key="provider.id"
              :label="provider.label || provider.id"
              :value="provider.id"
            />
          </el-select>
          <el-select
            v-model="filters.model"
            placeholder="全部模型"
            clearable
            filterable
            allow-create
            class="model-select"
            @change="reload"
          >
            <el-option v-for="model in facets.models" :key="model" :label="model" :value="model" />
          </el-select>
          <el-select
            v-model="filters.success"
            placeholder="全部状态"
            clearable
            class="success-select"
            @change="reload"
          >
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
          <el-input
            v-model="filters.recordingId"
            placeholder="录音 ID"
            clearable
            class="id-input"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            unlink-panels
            class="date-range"
            @change="reload"
          />
          <el-button :icon="Refresh" circle aria-label="刷新" title="刷新" @click="reload" />
          <el-button :icon="Search" @click="reload">查询</el-button>
          <el-button link @click="reset">重置</el-button>
        </div>
      </div>
      <div class="hint tiny dim">按时间倒序分页；点击任意行查看完整对话与原始 JSON。</div>
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
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="num" :title="formatTime(row.createdAt)">
              {{ formatSmartTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="阶段" width="126">
          <template #default="{ row }">
            <span class="chip" :class="aiStageMeta(row.stage).type">
              {{ aiStageMeta(row.stage).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="录音" width="86">
          <template #default="{ row }">
            <el-button
              v-if="row.recordingId"
              link
              type="primary"
              class="rec-link"
              @click.stop="openRecording(row)"
            >
              #{{ row.recordingId }}
            </el-button>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="供应商 / 模型" min-width="200">
          <template #default="{ row }">
            <div class="model-cell">
              <span class="model-provider">{{ row.providerLabel || row.providerId || '—' }}</span>
              <span class="mono tiny dim ellipsis" :title="row.endpoint || row.model">
                {{ row.model || '—' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="chip" :class="aiStatusMeta(row).type">{{ aiStatusMeta(row).label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="88" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatLatency(row.latencyMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Tokens 入/出" width="126" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatTokens(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成本" width="104" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatCostUsd(row.costMicros) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="请求/回复" width="112" align="right">
          <template #default="{ row }">
            <span class="num tiny dim">
              {{ formatChars(row.requestChars) }} / {{ formatChars(row.responseChars) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><ChatDotRound /></el-icon>
            <div class="empty-title">暂无 AI 调用记录</div>
            <div class="tiny dim">语义复筛、敏感词挖掘、embedding 调用都会自动留痕</div>
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
    <el-drawer v-model="detailVisible" size="680px" :with-header="false" append-to-body>
      <div class="drawer-head">
        <div>
          <div class="section-title">AI 对话详情</div>
          <div class="tiny dim">完整提示词、模型原始回复与调用用量</div>
        </div>
        <el-button
          :icon="Close"
          circle
          size="small"
          aria-label="关闭"
          title="关闭"
          @click="detailVisible = false"
        />
      </div>

      <div v-if="detailLoading" class="drawer-loading">
        <el-skeleton :rows="8" animated />
      </div>
      <div v-else-if="detailError" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ detailError }}</span>
      </div>
      <template v-else-if="detail">
        <el-descriptions :column="2" border size="small" class="detail-desc">
          <el-descriptions-item label="时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="阶段">
            <span class="chip" :class="aiStageMeta(detail.stage).type">
              {{ aiStageMeta(detail.stage).label }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <span class="chip" :class="aiStatusMeta(detail).type">
              {{ aiStatusMeta(detail).label }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="关联录音">
            <el-button
              v-if="detail.recordingId"
              link
              type="primary"
              @click="openRecording(detail)"
            >
              #{{ detail.recordingId }}
            </el-button>
            <span v-else class="dim">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.providerLabel || detail.providerId || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="模型">{{ detail.model || '—' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatLatency(detail.latencyMs) }}</el-descriptions-item>
          <el-descriptions-item label="Tokens 入/出">{{ formatTokens(detail) }}</el-descriptions-item>
          <el-descriptions-item label="成本">{{ formatCostUsd(detail.costMicros) }}</el-descriptions-item>
          <el-descriptions-item label="请求 / 回复">
            {{ formatChars(detail.requestChars) }} / {{ formatChars(detail.responseChars) }} 字符
          </el-descriptions-item>
          <el-descriptions-item label="端点" :span="2">
            <span class="mono tiny break-all">{{ detail.endpoint || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.errorMessage" class="error-banner detail-error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ detail.errorMessage }}</span>
        </div>

        <template v-if="messages.length">
          <div class="block-title">
            对话消息
            <span class="tiny dim">共 {{ messages.length }} 条，按请求顺序</span>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="ai-part">
            <div class="ai-part-head">
              <span class="ai-role" :class="message.role">{{ roleLabel(message.role) }}</span>
              <span class="tiny dim">{{ message.content.length }} 字符</span>
            </div>
            <pre class="ai-raw">{{ message.content }}</pre>
          </div>
        </template>
        <template v-else>
          <div class="block-title">请求内容</div>
          <pre class="ai-raw">{{ prettyJson(detail.requestJson) || '（请求体未留痕）' }}</pre>
        </template>

        <div class="block-title">模型原始回复</div>
        <pre v-if="detail.responseJson" class="ai-raw reply">{{ prettyJson(detail.responseJson) }}</pre>
        <div v-else class="tiny dim">（无回复）</div>

        <el-collapse v-if="detail.requestJson && messages.length" class="raw-collapse">
          <el-collapse-item title="查看原始请求 JSON" name="request">
            <pre class="ai-raw">{{ prettyJson(detail.requestJson) }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, Refresh, Search } from '@element-plus/icons-vue'
import api from '../api'
import { formatSmartTime, formatTime } from '../constants'
import {
  aiStageMeta,
  aiStatusMeta,
  formatChars,
  formatCostUsd,
  formatLatency,
  formatTokens,
  parseAiMessages,
  prettyJson,
  roleLabel
} from '../utils/aiChat'

const router = useRouter()

const DEFAULT_STAGES = ['SCREEN', 'MINING', 'EMBED_QUERY', 'EMBED_INDEX']

const filters = reactive({
  // 默认只看「语义复筛」对话；可切换阶段查看词挖掘 / embedding 调用
  stage: 'SCREEN',
  providerId: '',
  model: '',
  success: '',
  recordingId: ''
})
const dateRange = ref([])
const facets = reactive({ stages: [], providers: [], models: [] })

const stageOptions = computed(() => {
  const values = new Set([...DEFAULT_STAGES, ...(facets.stages || [])])
  return [...values].map((value) => ({ value, label: aiStageMeta(value).label }))
})

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 15
const loading = ref(false)
const error = ref('')

const detailVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const messages = computed(() => parseAiMessages(detail.value?.requestJson))

async function loadFacets() {
  try {
    const data = await api.getAiInvocationFacets()
    facets.stages = data.stages
    facets.providers = data.providers
    facets.models = data.models
  } catch {
    // 下拉候选失败不影响列表查询
  }
}

function normalizedRecordingId() {
  const value = String(filters.recordingId || '').trim()
  if (!value) return undefined
  if (!/^\d+$/.test(value)) {
    ElMessage.warning('录音 ID 需为数字')
    return null
  }
  return Number(value)
}

async function load() {
  const recordingId = normalizedRecordingId()
  if (recordingId === null) return
  loading.value = true
  error.value = ''
  try {
    const data = await api.listAiInvocations({
      page: page.value,
      size: pageSize,
      stage: filters.stage || undefined,
      providerId: filters.providerId || undefined,
      model: filters.model || undefined,
      success:
        filters.success === 'true' ? true : filters.success === 'false' ? false : undefined,
      recordingId,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1]
    })
    rows.value = data.items
    total.value = data.total
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e?.message || 'AI 对话记录加载失败'
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 0
  load()
}

function reset() {
  filters.stage = 'SCREEN'
  filters.providerId = ''
  filters.model = ''
  filters.success = ''
  filters.recordingId = ''
  dateRange.value = []
  reload()
}

async function openDetail(row) {
  if (!row?.id) return
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    detail.value = await api.getAiInvocation(row.id)
  } catch (e) {
    detailError.value = e?.message || 'AI 对话详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function openRecording(row) {
  if (!row?.recordingId) return
  detailVisible.value = false
  router.push({ path: '/recordings', query: { id: row.recordingId } })
}

onMounted(() => {
  loadFacets()
  load()
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
  gap: 8px;
  flex-wrap: wrap;
}
.stage-select {
  width: 132px;
}
.provider-select {
  width: 150px;
}
.model-select {
  width: 180px;
}
.success-select {
  width: 112px;
}
.id-input {
  width: 104px;
}
.date-range {
  width: 238px;
}
.hint {
  padding: 0 18px 14px;
  line-height: 1.7;
}

:deep(.row-clickable) {
  cursor: pointer;
}
.model-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.model-provider {
  color: var(--ink-700);
  font-size: 13px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rec-link {
  padding: 0;
  font-family: var(--font-mono);
}

.error-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 18px 12px;
  padding: 10px 12px;
  border: 1px solid #f7c9c9;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: #b42318;
  font-size: 12.5px;
}
.detail-error {
  margin: 0 0 16px;
}

.empty-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 28px 0;
}
.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  font-weight: 600;
  color: var(--ink-600);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}

.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.drawer-loading {
  padding: 4px 2px;
}
.detail-desc {
  margin-bottom: 16px;
}
.block-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  margin: 16px 0 10px;
}
.ai-part {
  margin-bottom: 14px;
}
.ai-part-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}
.ai-role {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
}
.ai-role.user {
  color: #7a5b00;
  background: var(--warn-soft);
  border-color: #f6dfb3;
}
.ai-role.assistant {
  color: var(--ok);
  background: var(--ok-soft);
  border-color: #cfe9d8;
}
.ai-raw {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  color: var(--ink-600);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 340px;
  overflow: auto;
}
.ai-raw.reply {
  background: #f7fbf8;
  border-color: #dcefe3;
}
.raw-collapse {
  margin-top: 18px;
}
:deep(.raw-collapse .el-collapse-item__header) {
  font-size: 12px;
  color: var(--ink-500);
}
.break-all {
  word-break: break-all;
}

@media (max-width: 900px) {
  .toolbar {
    padding: 12px 14px 8px;
  }
  .actions {
    width: 100%;
  }
  .stage-select,
  .provider-select,
  .model-select,
  .success-select,
  .id-input,
  .date-range {
    width: calc(50% - 4px);
  }
  .date-range {
    width: 100%;
  }
}
</style>
