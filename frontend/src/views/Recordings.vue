<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters">
          <button
            v-for="f in filters"
            :key="f.key"
            class="filter-chip"
            :class="{ on: statusFilter === f.key }"
            @click="applyFilter(f.key)"
          >
            {{ f.label }}
            <span v-if="f.count !== null" class="filter-count">{{ f.count }}</span>
          </button>
        </div>

        <div class="actions">
          <el-input
            v-model="keyword"
            placeholder="搜索文件名 / 转写内容"
            clearable
            class="search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" />
          <el-upload
            :show-file-list="false"
            :http-request="doUpload"
            :before-upload="beforeUpload"
            multiple
            accept="audio/*,.mp3,.wav,.m4a,.mp4,.webm,.ogg,.flac,.aac,.amr,.caf,.opus,.mov"
          >
            <el-button type="primary" :icon="UploadFilled">上传录音</el-button>
          </el-upload>
        </div>
      </div>
      <div class="hint">
        上传后自动执行 Whisper 转写 → DFA 初筛 → AI 语义复筛；初筛与复筛<span class="accent">均判定违规</span>的录音进入人工复检。单文件 ≤ 25MB。
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="() => 'row-clickable'"
        @row-click="openDetail"
      >
        <el-table-column label="录音" min-width="260">
          <template #default="{ row }">
            <div class="file-cell">
              <div class="file-icon"><el-icon><Microphone /></el-icon></div>
              <div class="file-meta">
                <div class="file-name ellipsis">{{ row.fileName }}</div>
                <div class="file-sub">
                  <span class="mono">#{{ row.id }}</span>
                  <span class="sep">·</span>
                  <span class="num">{{ formatDuration(row.durationSeconds) }}</span>
                  <span class="sep">·</span>
                  <span class="num">{{ formatTimeShort(row.uploadTime) }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="DFA 命中" width="104" align="center">
          <template #default="{ row }">
            <span v-if="row.hitCount" class="chip danger">
              <el-icon :size="11"><WarningFilled /></el-icon>{{ row.hitCount }} 处
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="AI 复筛结论" width="132" align="center">
          <template #default="{ row }">
            <span v-if="row.aiViolationTypeLabel" class="chip warn">{{ row.aiViolationTypeLabel }}</span>
            <span v-else-if="row.aiViolation === true" class="chip danger">违规</span>
            <span v-else-if="row.aiViolation === false" class="chip ok">判定正常</span>
            <span v-else-if="row.hitCount && PROCESSING_STATUSES.includes(row.status)" class="chip info">复筛中</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="132" align="center">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="复检人" width="112" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.reviewer"
              :content="`${row.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行'} · ${formatTime(row.reviewTime)}`"
              placement="top"
            >
              <span class="chip">{{ row.reviewer }}</span>
            </el-tooltip>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="176" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'NEEDS_REVIEW'"
              size="small"
              link
              type="danger"
              @click.stop="$router.push(`/review?id=${row.id}`)"
            >去复检</el-button>
            <el-button
              v-if="row.status === 'FAILED'"
              size="small"
              link
              type="warning"
              @click.stop="retry(row)"
            >重试</el-button>
            <el-popconfirm title="确认删除该录音（含文件与日志）？" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger" @click.stop>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Microphone /></el-icon>
            <div class="empty-title">还没有录音</div>
            <div class="tiny dim">上传客服通话录音，系统会自动完成转写、初筛与语义复筛</div>
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
    <el-drawer v-model="detailVisible" size="720px" :with-header="false">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <!-- 抽屉头 -->
          <div class="detail-head">
            <div class="detail-title">
              <div class="file-icon lg"><el-icon :size="18"><Microphone /></el-icon></div>
              <div class="min-w-0">
                <div class="detail-name ellipsis">{{ detail.fileName }}</div>
                <div class="detail-chips">
                  <StatusTag :status="detail.status" />
                  <span class="chip">#{{ detail.id }}</span>
                  <span class="chip">{{ formatDuration(detail.durationSeconds) }}</span>
                  <span class="chip">{{ formatSize(detail.fileSize) }}</span>
                  <span v-if="detail.language" class="chip">{{ detail.language }}</span>
                </div>
              </div>
            </div>
            <div class="detail-head-actions">
              <el-button
                v-if="detail.status === 'NEEDS_REVIEW'"
                type="danger"
                size="small"
                @click="$router.push(`/review?id=${detail.id}`)"
              >去人工复检</el-button>
              <el-button
                v-if="detail.status === 'FAILED'"
                type="warning"
                size="small"
                @click="retry(detail)"
              >重试</el-button>
              <el-button :icon="Close" circle size="small" @click="detailVisible = false" />
            </div>
          </div>

          <!-- 录音播放 -->
          <audio v-if="audioUrl" class="audio" controls preload="none" :src="audioUrl"></audio>

          <!-- 失败原因 -->
          <div v-if="detail.errorMessage" class="error-banner">
            <el-icon><CircleCloseFilled /></el-icon>
            <div>{{ detail.errorMessage }}</div>
          </div>

          <!-- AI 复筛 -->
          <section v-if="aiResult" class="block">
            <div class="block-title">AI 语义复筛</div>
            <div class="verdict" :class="aiResult.violation ? 'bad' : 'ok'">
              <div class="verdict-head">
                <span class="verdict-badge">
                  <el-icon :size="14">
                    <component :is="aiResult.violation ? 'WarningFilled' : 'CircleCheckFilled'" />
                  </el-icon>
                  {{ aiResult.violation ? '判定违规' : '判定正常' }}
                </span>
                <span v-if="aiTypeLabel" class="chip warn">{{ aiTypeLabel }}</span>
                <span class="spacer"></span>
                <span class="conf">
                  置信度
                  <b class="num">{{ aiResult.confidence != null ? (aiResult.confidence * 100).toFixed(0) + '%' : '—' }}</b>
                </span>
              </div>
              <div class="conf-track">
                <div
                  class="conf-fill"
                  :class="aiResult.violation ? 'bad' : 'ok'"
                  :style="{ width: (aiResult.confidence || 0) * 100 + '%' }"
                ></div>
              </div>
              <div class="verdict-reason">{{ aiResult.reason || '—' }}</div>
            </div>
          </section>

          <!-- 转写 -->
          <section class="block">
            <div class="block-head">
              <div class="block-title">录音转写</div>
              <el-button
                v-if="segments.length"
                link
                type="primary"
                size="small"
                @click="showSegments = !showSegments"
              >
                {{ showSegments ? '收起分段' : `查看分段 (${segments.length})` }}
              </el-button>
            </div>
            <TranscriptViewer
              :text="detail.transcript || '（暂无转写文本）'"
              :hits="dfaHits"
              :target-sentence="aiResult?.targetSentence"
            />
            <el-collapse-transition>
              <div v-show="showSegments && segments.length" class="segments">
                <div v-for="(s, i) in segments" :key="i" class="segment">
                  <span class="seg-time mono">{{ fmt(s.start) }} – {{ fmt(s.end) }}</span>
                  <span class="seg-text">{{ s.text }}</span>
                </div>
              </div>
            </el-collapse-transition>
          </section>

          <!-- 人工复检 -->
          <section v-if="detail.reviewResult" class="block">
            <div class="block-title">人工复检</div>
            <div class="review-grid">
              <div class="review-item">
                <span class="review-label">结论</span>
                <span
                  class="chip"
                  :class="detail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'"
                >
                  {{ detail.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
                </span>
              </div>
              <div class="review-item">
                <span class="review-label">类型</span>
                <span>{{ detail.violationTypeLabel || '—' }}</span>
              </div>
              <div class="review-item">
                <span class="review-label">复检人</span>
                <span>{{ detail.reviewer || '—' }}</span>
              </div>
              <div class="review-item">
                <span class="review-label">时间</span>
                <span class="num">{{ formatTime(detail.reviewTime) }}</span>
              </div>
              <div class="review-item span-2">
                <span class="review-label">备注</span>
                <span>{{ detail.reviewComment || '—' }}</span>
              </div>
            </div>
          </section>

          <!-- 处理轨迹 -->
          <section class="block">
            <div class="block-title">处理轨迹</div>
            <el-timeline v-if="logs.length" class="timeline">
              <el-timeline-item
                v-for="(l, i) in logs"
                :key="i"
                :type="l.level === 'ERROR' ? 'danger' : l.level === 'WARN' ? 'warning' : 'primary'"
                :timestamp="formatTime(l.createdAt)"
                size="normal"
              >
                <span class="log-stage">{{ stageName(l.stage) }}</span>
                <span class="log-msg">{{ l.message }}</span>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="dim tiny">暂无处理日志</div>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, UploadFilled, Close } from '@element-plus/icons-vue'
import api from '../api'
import StatusTag from '../components/StatusTag.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import { formatDuration, formatSize, formatTime, formatTimeShort, PROCESSING_STATUSES } from '../constants'

const route = useRoute()
const router = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 10
const loading = ref(false)
const statusFilter = ref('')
const keyword = ref('')
const overview = ref(null)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const logs = ref([])
const showSegments = ref(false)

const FILTERS = [
  { key: '', label: '全部', countKey: 'ALL' },
  { key: 'PROCESSING', label: '处理中', countKey: 'PROCESSING' },
  { key: 'NEEDS_REVIEW', label: '待复检', countKey: 'NEEDS_REVIEW' },
  { key: 'COMPLIANT', label: '自动通过', countKey: 'COMPLIANT' },
  { key: 'VIOLATION_CONFIRMED', label: '确认违规', countKey: 'VIOLATION_CONFIRMED' },
  { key: 'FALSE_POSITIVE', label: '误判放行', countKey: 'FALSE_POSITIVE' },
  { key: 'FAILED', label: '失败', countKey: 'FAILED' }
]

const filters = computed(() =>
  FILTERS.map((f) => {
    const counts = overview.value?.statusCounts
    let count = null
    if (counts) {
      if (f.countKey === 'ALL') count = overview.value?.totalUploads ?? 0
      else if (f.countKey === 'PROCESSING')
        count = PROCESSING_STATUSES.reduce((s, k) => s + (counts[k] || 0), 0)
      else count = counts[f.countKey] ?? 0
    }
    return { ...f, count }
  })
)

const dfaHits = computed(() => {
  try {
    return JSON.parse(detail.value?.dfaHitsJson || '[]')
  } catch {
    return []
  }
})
const aiResult = computed(() => {
  try {
    return JSON.parse(detail.value?.aiResultJson || 'null')
  } catch {
    return null
  }
})
/** AI 判定违规时的类型标签（NONE/无 不展示） */
const aiTypeLabel = computed(() => {
  const r = aiResult.value
  if (!r || r.violationType === 'NONE') return ''
  return r.violationTypeLabel || ''
})
const segments = computed(() => {
  try {
    return JSON.parse(detail.value?.segmentsJson || '[]')
  } catch {
    return []
  }
})
const audioUrl = computed(() => (detail.value?.filePath ? `/uploads/${detail.value.filePath}` : ''))

function fmt(sec) {
  const m = Math.floor(sec / 60)
  const s = Math.round(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const STAGE_NAMES = {
  PIPELINE: '流水线',
  TRANSCRIBE: 'Whisper 转写',
  DFA: 'DFA 初筛',
  AI: 'AI 复筛',
  REVIEW: '人工复检',
  MINING: '词典挖掘'
}
const stageName = (s) => STAGE_NAMES[s] || s

async function load() {
  loading.value = true
  try {
    const statuses =
      statusFilter.value === 'PROCESSING'
        ? PROCESSING_STATUSES
        : statusFilter.value
          ? [statusFilter.value]
          : undefined
    const data = await api.listRecordings({
      statuses,
      keyword: keyword.value || undefined,
      page: page.value,
      size: pageSize
    })
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  try {
    overview.value = await api.overview()
  } catch {
    // 忽略
  }
}

function applyFilter(key) {
  statusFilter.value = key
  reload()
}

function reload() {
  page.value = 0
  load()
}

function beforeUpload(file) {
  const limit = 25 * 1024 * 1024
  if (file.size > limit) {
    ElMessage.error(`${file.name} 超过 25MB，Whisper 单文件上限`)
    return false
  }
  return true
}

async function doUpload({ file, onSuccess, onError }) {
  try {
    const r = await api.upload(file)
    onSuccess(r)
    ElMessage.success(`上传成功 #${r.id}，已进入稽核流水线`)
    load()
    loadOverview()
  } catch (e) {
    onError(e)
  }
}

async function openDetail(row) {
  if (!row?.id) return
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  logs.value = []
  showSegments.value = false
  try {
    const [d, l] = await Promise.all([api.recordingDetail(row.id), api.recordingLogs(row.id)])
    detail.value = d
    logs.value = l || []
  } finally {
    detailLoading.value = false
  }
}

async function retry(row) {
  await api.retryRecording(row.id)
  ElMessage.success('已重新进入流水线')
  load()
  loadOverview()
  if (detail.value?.id === row.id) openDetail(row)
}

async function remove(row) {
  await api.deleteRecording(row.id)
  ElMessage.success('已删除')
  if (detail.value?.id === row.id) detailVisible.value = false
  load()
  loadOverview()
}

let timer = null
const hasProcessing = () => rows.value.some((r) => PROCESSING_STATUSES.includes(r.status))

onMounted(async () => {
  // 支持从工作台/其他页面带条件跳转
  const qs = String(route.query.statuses || '')
  if (qs) statusFilter.value = qs.split(',')[0] === 'FAILED' ? 'FAILED' : qs.split(',')[0]
  loadOverview()
  await load()
  if (route.query.id) {
    openDetail({ id: Number(route.query.id) })
  }
  timer = setInterval(() => {
    if (hasProcessing()) {
      load()
      loadOverview()
    }
  }, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
/* 工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.filters {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.filter-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.filter-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
  box-shadow: 0 4px 12px rgba(59, 110, 246, 0.24);
}
.filter-count {
  font-size: 11px;
  opacity: 0.72;
  font-variant-numeric: tabular-nums;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.search {
  width: 250px;
}
.hint {
  padding: 0 18px 14px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.accent {
  color: #c92c2c;
  font-weight: 600;
}

/* 表格 */
:deep(.row-clickable) {
  cursor: pointer;
}
.file-cell {
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
.file-icon.lg {
  width: 38px;
  height: 38px;
  border-radius: 11px;
}
.file-meta {
  min-width: 0;
}
.file-name {
  font-weight: 500;
  color: var(--ink-700);
}
.file-sub {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}
.sep {
  margin: 0 5px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* 抽屉 */
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.detail-title {
  display: flex;
  gap: 11px;
  min-width: 0;
}
.detail-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
  max-width: 420px;
}
.detail-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  flex-wrap: wrap;
}
.detail-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.min-w-0 {
  min-width: 0;
}

.audio {
  width: 100%;
  height: 38px;
  border-radius: var(--r-md);
}

.error-banner {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
  line-height: 1.6;
}

.block {
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 14px 16px;
  background: #fff;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.block-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 12px;
}
.block-head .block-title {
  margin-bottom: 12px;
}

/* AI 结论 */
.verdict {
  border-radius: var(--r-md);
  padding: 12px 14px;
  border: 1px solid var(--border);
  background: #fbfcfe;
}
.verdict.bad {
  background: linear-gradient(180deg, #fff6f6, #fffdfd);
  border-color: #f7d0d0;
}
.verdict.ok {
  background: linear-gradient(180deg, #f5fbf7, #fdfefd);
  border-color: #cfe9d8;
}
.verdict-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.verdict-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  font-weight: 600;
}
.verdict.bad .verdict-badge {
  color: #c92c2c;
}
.verdict.ok .verdict-badge {
  color: #12823b;
}
.conf {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.conf b {
  color: var(--ink-700);
  margin-left: 3px;
}
.conf-track {
  height: 6px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.07);
  margin: 10px 0;
  overflow: hidden;
}
.conf-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease;
}
.conf-fill.bad {
  background: linear-gradient(90deg, #f87171, #ef4444);
}
.conf-fill.ok {
  background: linear-gradient(90deg, #4ade80, #16a34a);
}
.verdict-reason {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

/* 分段 */
.segments {
  margin-top: 12px;
  border-top: 1px dashed var(--border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-height: 240px;
  overflow-y: auto;
}
.segment {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.6;
}
.seg-time {
  flex: none;
  color: var(--brand-600);
  background: var(--brand-50);
  border-radius: 5px;
  padding: 1px 7px;
  height: 21px;
}
.seg-text {
  color: var(--el-text-color-regular);
}

/* 复检 */
.review-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  font-size: 12.5px;
}
.review-item {
  display: flex;
  gap: 10px;
  align-items: center;
}
.review-item.span-2 {
  grid-column: span 2;
  align-items: flex-start;
}
.review-label {
  color: var(--el-text-color-placeholder);
  flex: none;
  min-width: 42px;
}

/* 轨迹 */
.timeline {
  padding-left: 2px;
}
.log-stage {
  display: inline-block;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--brand-600);
  background: var(--brand-50);
  border-radius: 5px;
  padding: 1px 7px;
  margin-right: 8px;
}
.log-msg {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  line-height: 1.65;
}
</style>
