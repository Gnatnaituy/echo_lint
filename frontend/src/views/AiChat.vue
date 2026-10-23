<template>
  <div class="page stack">
    <!-- 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">{{ t('aiChat.title') }}</div>
          <span class="tiny dim">{{ t('aiChat.subtitle') }}</span>
        </div>
        <div class="actions">
          <el-button
            :icon="Refresh"
            circle
            :aria-label="t('common.refresh')"
            :title="t('common.refresh')"
            @click="reload"
          />
          <el-button :icon="Search" @click="reload">{{ t('aiChat.search') }}</el-button>
          <el-button link @click="reset">{{ t('common.reset') }}</el-button>
        </div>
      </div>
      <div class="filters">
        <el-select
          v-model="filters.stage"
          :placeholder="t('aiChat.allStages')"
          clearable
          class="filter-select stage-select"
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
          :placeholder="t('aiChat.allProviders')"
          clearable
          class="filter-select provider-select"
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
          :placeholder="t('aiChat.allModels')"
          clearable
          filterable
          allow-create
          class="filter-select model-select"
          @change="reload"
        >
          <el-option v-for="model in facets.models" :key="model" :label="model" :value="model" />
        </el-select>
        <SegmentedControl
          v-model="filters.success"
          :options="successOptions"
          :aria-label="t('aiChat.allStatuses')"
          @change="reload"
        />
        <el-input
          v-model="filters.recordingId"
          :placeholder="t('aiChat.recordingIdPlaceholder')"
          clearable
          class="filter-select id-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <div class="date-filter">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            :start-placeholder="t('common.startDate')"
            :end-placeholder="t('common.endDate')"
            unlink-panels
            @change="reload"
          />
        </div>
      </div>
      <div class="hint tiny dim">{{ t('aiChat.listHint') }}</div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">{{ t('common.retry') }}</el-button>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        :row-class-name="() => 'row-clickable'"
        @row-click="openDetail"
      >
        <el-table-column :label="t('aiChat.time')" width="132">
          <template #default="{ row }">
            <span class="num" :title="formatTime(row.createdAt)">
              {{ formatSmartTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('aiChat.stage')" width="190">
          <template #default="{ row }">
            <span class="chip" :class="aiStageMeta(row.stage).type">
              {{ aiStageMeta(row.stage).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('aiChat.recording')" width="110">
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
        <el-table-column :label="t('aiChat.providerModel')" min-width="168">
          <template #default="{ row }">
            <div class="model-cell">
              <span class="model-provider">{{ row.providerLabel || row.providerId || '—' }}</span>
              <span class="mono tiny dim ellipsis" :title="row.endpoint || row.model">
                {{ row.model || '—' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="84">
          <template #default="{ row }">
            <span class="chip" :class="aiStatusMeta(row).type">{{ aiStatusMeta(row).label }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!compactColumns" :label="t('aiChat.latency')" width="92" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatLatency(row.latencyMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('aiChat.tokens')" width="112" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatTokens(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('aiChat.cost')" width="116" align="right">
          <template #default="{ row }">
            <span class="num">{{ formatCostUsd(row.costMicros) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!compactColumns" :label="t('aiChat.requestResponse')" width="156" align="right">
          <template #default="{ row }">
            <span class="num tiny dim">
              {{ formatChars(row.requestChars) }} / {{ formatChars(row.responseChars) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ t('common.view') }}</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><ChatDotRound /></el-icon>
            <div class="empty-title">{{ t('aiChat.emptyTitle') }}</div>
            <div class="tiny dim">{{ t('aiChat.emptyHint') }}</div>
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
          <div class="section-title">{{ t('aiChat.detailTitle') }}</div>
          <div class="tiny dim">{{ t('aiChat.detailSubtitle') }}</div>
        </div>
        <el-button
          :icon="Close"
          circle
          size="small"
          :aria-label="t('common.close')"
          :title="t('common.close')"
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
        <el-descriptions :column="compactColumns ? 1 : 2" border size="small" class="detail-desc">
          <el-descriptions-item :label="t('aiChat.time')">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.stage')">
            <span class="chip" :class="aiStageMeta(detail.stage).type">
              {{ aiStageMeta(detail.stage).label }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            <span class="chip" :class="aiStatusMeta(detail).type">
              {{ aiStatusMeta(detail).label }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.recordingLinked')">
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
          <el-descriptions-item :label="t('aiChat.provider')">
            {{ detail.providerLabel || detail.providerId || '—' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.model')">{{ detail.model || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.latency')">{{ formatLatency(detail.latencyMs) }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.tokens')">{{ formatTokens(detail) }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.cost')">{{ formatCostUsd(detail.costMicros) }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.requestResponseDetail')">
            {{ charsPair(detail) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('aiChat.endpoint')" :span="2">
            <span class="mono tiny break-all">{{ detail.endpoint || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.errorMessage" class="error-banner detail-error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ detail.errorMessage }}</span>
        </div>

        <template v-if="messages.length">
          <div class="block-title">
            {{ t('aiChat.messagesTitle') }}
            <span class="tiny dim">
              {{ tn('aiChat.messagesCount', messages.length) }}
            </span>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="ai-part">
            <div class="ai-part-head">
              <span class="ai-role" :class="message.role">{{ roleLabel(message.role) }}</span>
              <span class="tiny dim">
                {{ tn('aiChat.charsCount', message.content.length) }}
              </span>
            </div>
            <pre class="ai-raw">{{ message.content }}</pre>
          </div>
        </template>
        <template v-else>
          <div class="block-title">{{ t('aiChat.requestContent') }}</div>
          <pre class="ai-raw">{{ prettyJson(detail.requestJson) || t('aiChat.requestNotRecorded') }}</pre>
        </template>

        <div class="block-title">{{ t('aiChat.rawResponse') }}</div>
        <pre v-if="detail.responseJson" class="ai-raw reply">{{ prettyJson(detail.responseJson) }}</pre>
        <div v-else class="tiny dim">{{ t('aiChat.noResponse') }}</div>

        <el-collapse v-if="detail.requestJson && messages.length" class="raw-collapse">
          <el-collapse-item :title="t('aiChat.viewRawRequest')" name="request">
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
import SegmentedControl from '../components/SegmentedControl.vue'
import { formatSmartTime, formatTime } from '../constants'
import { useI18n } from '../i18n'
import { useElementWidth } from '../utils/elementWidth'
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

const { t, tn } = useI18n()

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

/* 枚举型过滤统一用分段控件：全部 / 成功 / 失败 */
const successOptions = computed(() => [
  { value: '', label: t('aiChat.allStatuses') },
  { value: 'true', label: t('aiChat.filterSuccess') },
  { value: 'false', label: t('aiChat.filterFailed') }
])

const stageOptions = computed(() => {
  const values = new Set([...DEFAULT_STAGES, ...(facets.stages || [])])
  return [...values].map((value) => ({ value, label: aiStageMeta(value).label }))
})

const rows = ref([])
/* 窄屏收起「耗时」「请求/回复」（详情抽屉里仍可查），保证固定操作列不压住成本/Tokens */
const tableRef = ref(null)
const tableWidth = useElementWidth(tableRef)
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 1060)
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

/** 请求 / 回复字符数：两段数值合并为一个插值展示 */
function charsPair(row) {
  return tn('aiChat.charsCount', `${formatChars(row?.requestChars)} / ${formatChars(row?.responseChars)}`)
}

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
    ElMessage.warning(t('aiChat.recordingIdNumeric'))
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
    error.value = e?.message || t('aiChat.loadFailed')
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
    detailError.value = e?.message || t('aiChat.detailLoadFailed')
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
  gap: 10px 16px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
/* 筛选项统一 32px 高、10px 间距，窄屏自然换行 */
.filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 0 18px 4px;
}
.filters > * {
  flex: none;
}
/* el-date-picker 不透传 class，用外层容器约束日期区间宽度 */
.date-filter {
  width: 300px;
  flex: none;
}
.date-filter :deep(.el-date-editor) {
  width: 100%;
  flex: none;
}
.filter-select {
  height: 32px;
}
.stage-select {
  width: 186px;
}
.provider-select {
  width: 150px;
}
.model-select {
  width: 172px;
}
.id-input {
  width: 110px;
}
.hint {
  padding: 6px 18px 14px;
  line-height: 1.7;
}

:deep(.row-clickable) {
  cursor: pointer;
}
/* 数值列不换行：耗时 / Tokens / 成本 / 请求回复 保持单行右对齐 */
:deep(.el-table .num) {
  white-space: nowrap;
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
  .filters {
    padding: 0 14px 4px;
  }
  .stage-select,
  .provider-select,
  .model-select,
  .id-input {
    width: calc(50% - 5px);
  }
  .date-filter {
    width: 100%;
  }
}
</style>
