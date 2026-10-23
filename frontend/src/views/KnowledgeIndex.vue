<template>
  <div class="stack">
    <!-- 工具栏 / 筛选 -->
    <section class="panel">
      <div class="toolbar">
        <div class="toolbar-head">
          <div class="row-wrap">
            <span class="chip info">{{ t('common.total', { total }) }}</span>
            <el-tooltip v-if="backgroundError" :content="backgroundError" placement="bottom">
              <span class="chip warn">{{ t('knowledgeIndex.backgroundFailed') }}</span>
            </el-tooltip>
            <el-tooltip v-if="settingsError" :content="settingsError" placement="bottom">
              <span class="chip">{{ t('knowledgeIndex.summary.unavailable') }}</span>
            </el-tooltip>
          </div>
          <div class="toolbar-actions">
            <el-button :icon="Refresh" :loading="loading || summaryLoading" @click="refresh">
              {{ t('common.refresh') }}
            </el-button>
          </div>
        </div>
        <div class="toolbar-filters">
          <el-input
            v-model="keyword"
            :placeholder="t('knowledgeIndex.keywordPlaceholder')"
            clearable
            class="search"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <SegmentedControl
            v-model="indexStatusFilter"
            :options="indexStatusOptions"
            :aria-label="t('knowledgeIndex.allIndexStatuses')"
            @change="applyFilters"
          />
          <SegmentedControl
            v-model="sourceTypeFilter"
            :options="sourceTypeOptions"
            :aria-label="t('knowledgeIndex.allSourceTypes')"
            @change="applyFilters"
          />
          <el-button type="primary" :icon="Search" :loading="loading" @click="applyFilters">
            {{ t('knowledgeIndex.query') }}
          </el-button>
          <el-button link @click="resetFilters">{{ t('common.reset') }}</el-button>
        </div>
        <div class="hint tiny dim">
          {{ t('knowledgeIndex.intro') }}
          <span class="sep">·</span>
          {{ t('knowledgeIndex.summary.embeddingValue', { value: summaryEmbeddingText }) }}
          <span class="sep">·</span>
          {{ t('knowledgeIndex.summary.lastReindex', { time: summaryLastReindexText }) }}
          <span class="sep">·</span>
          {{ summaryVectorText }}
        </div>
      </div>
      <div class="toolbar-foot">
        <span class="tiny dim">
          {{ t('knowledgeIndex.autoRefreshHint', { seconds: REFRESH_SECONDS }) }}
        </span>
        <span v-if="lastLoadedAt" class="tiny dim">
          · {{ t('knowledgeIndex.lastUpdated', { time: lastLoadedAt }) }}
        </span>
      </div>
    </section>

    <!-- 汇总卡：全部来自 GET /api/settings/rag 的 index + embedding 段 -->
    <div v-loading="summaryLoading" class="kpi-grid">
      <StatCard
        :label="t('knowledgeIndex.kpi.total')"
        :value="statText(indexInfo.knowledgeTotal)"
        icon="Files"
        tone="brand"
        :sub="t('knowledgeIndex.kpi.totalSub')"
      />
      <StatCard
        :label="t('knowledgeIndex.kpi.ready')"
        :value="statText(indexInfo.knowledgeReady)"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="t('knowledgeIndex.kpi.readySub')"
      />
      <StatCard
        :label="t('knowledgeIndex.kpi.processing')"
        :value="statText(indexInfo.knowledgePending)"
        icon="Loading"
        tone="warn"
        :sub="t('knowledgeIndex.kpi.processingSub')"
      />
      <StatCard
        :label="t('knowledgeIndex.kpi.failed')"
        :value="statText(indexInfo.knowledgeFailed)"
        icon="WarningFilled"
        tone="danger"
        :sub="t('knowledgeIndex.kpi.failedSub')"
      />
    </div>

    <!-- 文档列表 -->
    <section ref="tableWrap" class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="refresh">
          {{ t('common.retry') }}
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" @row-click="openChunks">
        <el-table-column :label="t('knowledgeIndex.column.document')" min-width="210">
          <template #default="{ row }">
            <div class="doc-cell">
              <div class="doc-text">
                <div class="strong ellipsis" :title="row.title">
                  {{ row.title || t('knowledge.untitledEntry') }}
                </div>
                <div class="tiny dim mono">#{{ row.id ?? '—' }}</div>
                <div
                  v-if="row.sourceFilename"
                  class="tiny dim source-file"
                  :title="row.sourceFilename"
                >
                  <el-icon :size="12"><Document /></el-icon>
                  <span class="source-name">{{ row.sourceFilename }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="!narrowColumns" :label="t('common.category')" width="110">
          <template #default="{ row }">
            <span v-if="row.category" class="chip info">{{ categoryLabel(row.category) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column v-if="!narrowColumns" :label="t('knowledge.source')" width="132">
          <template #default="{ row }">
            <span v-if="row.sourceType" class="chip" :class="sourceChipType(row.sourceType)">
              {{ sourceLabel(row.sourceType) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('knowledge.indexStatus')"
          width="118"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <el-tooltip
              v-if="isFailedIndex(row)"
              :content="row.indexError || t('knowledge.indexFailedHint')"
              placement="top"
              :show-after="200"
              :popper-style="{ maxWidth: '420px', wordBreak: 'break-all' }"
            >
              <span class="chip danger">{{ indexStatusMeta(row.indexStatus).label }}</span>
            </el-tooltip>
            <span
              v-else-if="row.indexStatus"
              class="chip"
              :class="indexStatusMeta(row.indexStatus).type"
            >
              {{ indexStatusMeta(row.indexStatus).label }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('knowledge.chunkCount')" width="116" align="right">
          <template #default="{ row }">
            <span v-if="row.chunkCount !== null" class="num">{{ row.chunkCount }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('knowledge.indexedAt')" width="180">
          <template #default="{ row }">
            <span v-if="row.indexedAt" class="num" :title="formatRelative(row.indexedAt)">
              {{ formatTime(row.indexedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column v-if="!compactColumns" :label="t('common.updatedAt')" width="180">
          <template #default="{ row }">
            <span v-if="row.updatedAt" class="num dim" :title="formatRelative(row.updatedAt)">
              {{ formatTime(row.updatedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.actions')" width="176" align="right" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button size="small" link type="primary" @click.stop="openChunks(row)">
                {{ t('knowledgeIndex.openChunks') }}
              </el-button>
              <el-button
                size="small"
                link
                type="primary"
                :loading="reindexingId === row.id"
                @click.stop="reindexRow(row)"
              >
                {{ t('knowledge.reindex') }}
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Tickets /></el-icon>
            <div class="empty-title">
              {{ hasFilters
                ? t('knowledgeIndex.emptyFilteredTitle')
                : t('knowledgeIndex.emptyTitle') }}
            </div>
            <div class="tiny dim">
              {{ hasFilters
                ? t('knowledgeIndex.emptyFilteredHint')
                : t('knowledgeIndex.emptyHint') }}
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
          @current-change="onPageChange"
        />
      </div>
    </section>

    <!-- 分块明细 -->
    <el-dialog
      v-model="chunksVisible"
      :title="t('knowledgeIndex.chunks.title')"
      width="860px"
      top="6vh"
      append-to-body
      class="chunks-dialog"
      @closed="resetChunks"
    >
      <div v-loading="chunksLoading" class="chunk-body">
        <div v-if="chunksError" class="error-banner">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ chunksError }}</span>
          <el-button link type="primary" size="small" @click="loadChunks">
            {{ t('common.retry') }}
          </el-button>
        </div>

        <template v-else>
          <div class="chunk-head">
            <div class="min-w-0">
              <div class="chunk-title" :title="chunksDisplayTitle">
                {{ chunksDisplayTitle }}
              </div>
              <div class="chunk-chips">
                <span class="chip" :class="chunksStatusMeta.type">{{ chunksStatusMeta.label }}</span>
                <span class="chip info">
                  {{ t('knowledgeIndex.chunks.totalChunks', { count: chunks.total }) }}
                </span>
              </div>
            </div>
          </div>

          <div class="index-grid">
            <div class="index-item">
              <span class="tiny dim">{{ t('knowledgeIndex.chunks.fingerprint') }}</span>
              <span class="mono ellipsis" :title="chunks.embeddingHash || '—'">
                {{ shortHash(chunks.embeddingHash) || '—' }}
              </span>
            </div>
            <div class="index-item">
              <span class="tiny dim">{{ t('knowledgeIndex.chunks.embedding') }}</span>
              <span class="mono ellipsis" :title="chunksEmbeddingText">{{ chunksEmbeddingText }}</span>
            </div>
            <div class="index-item">
              <span class="tiny dim">{{ t('knowledge.indexedAt') }}</span>
              <span class="num">{{ chunks.indexedAt ? formatTime(chunks.indexedAt) : '—' }}</span>
            </div>
          </div>

          <div v-if="chunks.indexError" class="index-error">
            <el-icon :size="14"><WarningFilled /></el-icon>
            <span>{{ chunks.indexError }}</span>
          </div>

          <el-table :data="chunks.items" class="chunk-table">
            <el-table-column type="expand" width="38">
              <template #default="{ row }">
                <pre class="chunk-content">{{ row.content || t('knowledgeIndex.chunks.emptyContent') }}</pre>
              </template>
            </el-table-column>
            <el-table-column :label="'#'" width="60" align="center">
              <template #default="{ row }">
                <span class="num">{{ row.chunkIndex ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('knowledgeIndex.chunks.column.content')" min-width="210">
              <template #default="{ row }">
                <div class="chunk-snippet">{{ row.content || t('knowledgeIndex.chunks.emptyContent') }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('knowledgeIndex.chunks.column.contentHash')" width="132">
              <template #default="{ row }">
                <span class="mono tiny" :title="row.contentHash || '—'">
                  {{ shortHash(row.contentHash) || '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('knowledgeIndex.chunks.column.embedding')" width="206">
              <template #default="{ row }">
                <span class="mono tiny ellipsis" :title="rowEmbeddingText(row)">
                  {{ row.embeddingModel || '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.updatedAt')" width="130">
              <template #default="{ row }">
                <span v-if="row.updatedAt" class="num tiny" :title="formatRelative(row.updatedAt)">
                  {{ formatTimeShort(row.updatedAt) }}
                </span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>

            <template #empty>
              <div class="empty-block">
                <el-icon :size="30" class="empty-icon"><Tickets /></el-icon>
                <div class="empty-title">{{ t('knowledgeIndex.chunks.emptyTitle') }}</div>
                <div class="tiny dim">{{ t('knowledgeIndex.chunks.emptyHint') }}</div>
              </div>
            </template>
          </el-table>

          <div v-if="chunks.total > 0" class="pager chunk-pager">
            <el-pagination
              layout="total, prev, pager, next"
              :total="chunks.total"
              :page-size="CHUNKS_PAGE_SIZE"
              :current-page="chunksPage + 1"
              @current-change="onChunksPageChange"
            />
          </div>
        </template>
      </div>

      <template #footer>
        <el-button @click="chunksVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import SegmentedControl from '../components/SegmentedControl.vue'
import { formatRelative, formatTime, formatTimeShort } from '../constants'
import { useElementWidth } from '../utils/elementWidth'
import { knowledgeCategoryMessageKey } from '../utils/knowledge'
import { embeddingLabel, normalizeKnowledgeChunks, shortHash } from '../utils/knowledgeIndex'
import { INDEX_STATUS_META, indexStatusMeta, isIndexInProgress } from '../utils/rag'
import { useI18n } from '../i18n'

const { t } = useI18n()

const pageSize = 20
const CHUNKS_PAGE_SIZE = 10
/** 当前页存在处理中条目时的静默刷新间隔（秒） */
const REFRESH_SECONDS = 4

/* ---------- 汇总卡（GET /api/settings/rag） ---------- */

const settings = ref(null)
const summaryLoading = ref(false)
const settingsError = ref('')

const indexInfo = computed(() => settings.value?.index || {})
const embeddingInfo = computed(() => settings.value?.embedding || {})

/** 缺字段显示 —：不臆造汇总数字 */
const statText = (value) =>
  value === null || value === undefined || Number.isNaN(value) ? '—' : value

const summaryEmbeddingText = computed(
  () => embeddingLabel(embeddingInfo.value.model, embeddingInfo.value.dimensions) || '—'
)
const summaryLastReindexText = computed(() =>
  indexInfo.value.lastReindexAt ? formatTime(indexInfo.value.lastReindexAt) : '—'
)
const summaryVectorText = computed(() => {
  const available = indexInfo.value.vectorAvailable
  if (available === true) return t('knowledgeIndex.summary.vectorAvailable')
  if (available === false) return t('knowledgeIndex.summary.vectorUnavailable')
  return '—'
})

/** 403 / 404 给出可读降级文案（设置接口 silent，不弹全局错误） */
function summaryErrorMessage(err) {
  const status = err?.status
  if (status === 403) return t('knowledgeIndex.forbidden')
  if (status === 404) return t('knowledgeIndex.notAvailable')
  return err?.message || t('knowledgeIndex.summary.unavailable')
}

async function loadSummary() {
  if (summaryLoading.value) return
  summaryLoading.value = true
  try {
    settings.value = await api.getRagSettings()
    settingsError.value = ''
  } catch (e) {
    settingsError.value = summaryErrorMessage(e)
  } finally {
    summaryLoading.value = false
  }
}

/* ---------- 列表 ---------- */

const tableWrap = ref(null)
const tableWidth = useElementWidth(tableWrap)
/** 内容区放不下时先收起更新时间，再收起分类 / 来源（核心列始终可见） */
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 1170)
const narrowColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 1000)

const rows = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const backgroundError = ref('')
const lastLoadedAt = ref('')

const keyword = ref('')
const indexStatusFilter = ref('')
const sourceTypeFilter = ref('')

const hasFilters = computed(
  () => !!(keyword.value.trim() || indexStatusFilter.value || sourceTypeFilter.value)
)

const INDEX_STATUS_OPTIONS = Object.keys(INDEX_STATUS_META).map((value) => ({
  value,
  get label() {
    return INDEX_STATUS_META[value].label
  }
}))

const SOURCE_TYPE_OPTIONS = [
  {
    value: 'DOCUMENT',
    get label() {
      return t('knowledge.sourceDocument')
    }
  },
  {
    value: 'MANUAL',
    get label() {
      return t('knowledge.sourceManual')
    }
  }
]

/* 枚举型过滤统一用分段控件：索引状态 / 来源，均带「全部」项 */
const indexStatusOptions = computed(() => [
  { value: '', label: t('knowledgeIndex.allIndexStatuses') },
  ...INDEX_STATUS_OPTIONS
])

const sourceTypeOptions = computed(() => [
  { value: '', label: t('knowledgeIndex.allSourceTypes') },
  ...SOURCE_TYPE_OPTIONS
])

/** 403 / 404 给出可读降级文案（列表接口 silent，不弹全局错误） */
function indexErrorMessage(err, fallback = t('knowledgeIndex.loadFailed')) {
  const status = err?.status
  if (status === 403) return t('knowledgeIndex.forbidden')
  if (status === 404) return t('knowledgeIndex.notAvailable')
  return err?.message || fallback
}

function nowText() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function currentParams() {
  return {
    keyword: keyword.value.trim() || undefined,
    indexStatus: indexStatusFilter.value || undefined,
    sourceType: sourceTypeFilter.value || undefined,
    page: page.value,
    size: pageSize
  }
}

// 同一时刻只允许一个列表请求；手动操作在请求中记为 pending，结束后立即补拉
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
  /** 筛选后当前页越界：回退到最后一页补拉（不能在 inflight 期间递归） */
  let clampPage = false
  try {
    const data = await api.listKnowledge(currentParams())
    rows.value = data.items
    total.value = data.total
    error.value = ''
    backgroundError.value = ''
    lastLoadedAt.value = nowText()
    if (!data.items.length && page.value > 0 && data.total > 0) {
      page.value = Math.max(0, Math.ceil(data.total / pageSize) - 1)
      clampPage = true
    }
  } catch (e) {
    if (background) {
      // 自动刷新失败：保留已加载数据，仅在头部提示，避免每 4 秒清空列表
      backgroundError.value = indexErrorMessage(e, t('knowledgeIndex.backgroundFailed'))
    } else {
      rows.value = []
      total.value = 0
      error.value = indexErrorMessage(e)
    }
  } finally {
    inflight = false
    if (!background) loading.value = false
    const pending = pendingLoad
    pendingLoad = null
    if (clampPage || pending !== null) {
      load(pending || { background })
    }
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function resetFilters() {
  keyword.value = ''
  indexStatusFilter.value = ''
  sourceTypeFilter.value = ''
  page.value = 0
  load()
}

function onPageChange(next) {
  page.value = next - 1
  load()
}

async function refresh() {
  await Promise.all([load(), loadSummary()])
}

/* ---------- 展示辅助 ---------- */

function isFailedIndex(row) {
  return String(row?.indexStatus || '').toUpperCase() === 'FAILED'
}

/** 分类值是持久化中文（写入后端），展示时按候选顺序映射为当前语言文案 */
function categoryLabel(category) {
  const key = knowledgeCategoryMessageKey(category)
  return key ? t(key) : category
}

/** 来源枚举 -> 文案；未知值原样展示 */
function sourceLabel(type) {
  const value = String(type || '').toUpperCase()
  if (value === 'DOCUMENT') return t('knowledge.sourceDocument')
  if (value === 'MANUAL') return t('knowledge.sourceManual')
  return type
}

function sourceChipType(type) {
  return String(type || '').toUpperCase() === 'DOCUMENT' ? 'brand' : 'info'
}

/* ---------- 重建索引 ---------- */

const reindexingId = ref(null)

async function reindexRow(row) {
  if (!row?.id || reindexingId.value != null) return
  reindexingId.value = row.id
  try {
    await api.reindexKnowledge(row.id)
    ElMessage.success(
      t('knowledgeIndex.reindexQueued', { title: row.title || row.id })
    )
    // 后台已排队：静默刷新列表与汇总，等待索引状态推进
    await Promise.all([load({ background: true }), loadSummary()])
  } catch {
    // 请求层已统一提示（403 / 404 / 后端校验失败），避免重复 toast
  } finally {
    reindexingId.value = null
  }
}

/* ---------- 分块明细 ---------- */

const chunksVisible = ref(false)
const chunksLoading = ref(false)
const chunksError = ref('')
const chunks = ref(normalizeKnowledgeChunks(null))
const chunksPage = ref(0)
const chunksEntryId = ref(null)
const fallbackTitle = ref('')

// 快速翻页 / 重开对话框时丢弃过期响应
let chunksToken = 0

const chunksDisplayTitle = computed(
  () => chunks.value.title || fallbackTitle.value || t('knowledge.untitledEntry')
)
const chunksStatusMeta = computed(() => indexStatusMeta(chunks.value.indexStatus))
const chunksEmbeddingText = computed(
  () =>
    embeddingLabel(chunks.value.embeddingModel, chunks.value.embeddingDimensions) || '—'
)

function rowEmbeddingText(row) {
  return embeddingLabel(row?.embeddingModel, row?.embeddingDim) || '—'
}

function chunksErrorMessage(err) {
  const status = err?.status
  if (status === 403) return t('knowledgeIndex.chunks.forbidden')
  if (status === 404) return t('knowledgeIndex.chunks.notAvailable')
  return err?.message || t('knowledgeIndex.chunks.loadFailed')
}

async function loadChunks() {
  const id = chunksEntryId.value
  if (id == null) return
  const token = ++chunksToken
  chunksLoading.value = true
  chunksError.value = ''
  try {
    const data = await api.listKnowledgeChunks(id, {
      page: chunksPage.value,
      size: CHUNKS_PAGE_SIZE
    })
    if (token !== chunksToken) return
    chunks.value = data
  } catch (e) {
    if (token !== chunksToken) return
    chunks.value = normalizeKnowledgeChunks(null)
    chunksError.value = chunksErrorMessage(e)
  } finally {
    if (token === chunksToken) chunksLoading.value = false
  }
}

function openChunks(row) {
  if (!row?.id) return
  chunksEntryId.value = row.id
  fallbackTitle.value = row.title || ''
  chunksPage.value = 0
  chunks.value = normalizeKnowledgeChunks(null)
  chunksError.value = ''
  chunksVisible.value = true
  loadChunks()
}

function onChunksPageChange(next) {
  chunksPage.value = next - 1
  loadChunks()
}

function resetChunks() {
  chunksToken += 1
  chunksEntryId.value = null
  fallbackTitle.value = ''
  chunksPage.value = 0
  chunks.value = normalizeKnowledgeChunks(null)
  chunksError.value = ''
  chunksLoading.value = false
}

/* ---------- 自动刷新 ---------- */

// 当前页存在 PENDING / PARSING / INDEXING 条目时每 4s 静默刷新；页面隐藏时跳过
let timer = null

function tick() {
  if (document.hidden || inflight) return
  if (!rows.value.some((row) => isIndexInProgress(row.indexStatus))) return
  load({ background: true })
}

function onVisibilityChange() {
  if (document.hidden || inflight) return
  if (!rows.value.some((row) => isIndexInProgress(row.indexStatus))) return
  load({ background: true })
}

onMounted(() => {
  load()
  loadSummary()
  timer = setInterval(tick, REFRESH_SECONDS * 1000)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  timer = null
  chunksToken += 1
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
/* 汇总卡 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 1080px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}

/* 工具栏 */
.toolbar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 18px 10px;
}
.toolbar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.toolbar-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  width: 230px;
}
.hint {
  line-height: 1.7;
}
.sep {
  margin: 0 5px;
}
.toolbar-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 18px 12px;
}

/* 文档列 */
.doc-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.doc-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.min-w-0 {
  min-width: 0;
}
.ellipsis {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-file {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  margin-top: 2px;
}
.source-file .el-icon {
  flex: none;
}
.source-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 操作列：链接按钮用 gap 控制间距，避免列宽吃紧后折行 */
.op-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  white-space: nowrap;
}
.op-cell :deep(.el-button + .el-button) {
  margin-left: 0;
}
/* 窄列（索引状态 / 分块数）用 12px 内边距，避免胶囊被 ellipsis 截出「…」 */
:deep(.col-tight .cell) {
  padding-left: 12px;
  padding-right: 12px;
}
:deep(.el-table__row) {
  cursor: pointer;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}
.empty-icon {
  color: var(--ink-300);
}

/* 错误横幅（与任务队列 / 知识库页一致） */
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

/* ---------- 分块明细对话框 ---------- */
.chunk-body {
  min-height: 180px;
}
.chunk-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.chunk-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--ink-900);
  line-height: 1.45;
  word-break: break-word;
}
.chunk-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
/* 与知识库详情一致的索引信息网格 */
.index-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.index-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  font-size: 12.5px;
}
.index-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: #b3261e;
  font-size: 12.5px;
  line-height: 1.65;
  word-break: break-word;
}
.chunk-table {
  margin-top: 14px;
}
.chunk-snippet {
  display: -webkit-box;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.chunk-content {
  max-height: 260px;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
}
.chunk-pager {
  padding: 10px 0 0;
}
/* 对话框内错误横幅不再叠加左右外边距（由 dialog body 负责留白） */
.chunk-body .error-banner {
  margin: 0;
}

@media (max-width: 920px) {
  :global(.chunks-dialog) {
    width: 94% !important;
  }
  .index-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
