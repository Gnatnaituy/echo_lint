<template>
  <div>
    <!-- 工具栏 -->
    <el-card shadow="never" class="toolbar">
      <div class="toolbar-row">
        <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width: 180px" @change="reload">
          <el-option label="待人工复检" value="NEEDS_REVIEW" />
          <el-option label="自动通过" value="COMPLIANT" />
          <el-option label="确认违规" value="VIOLATION_CONFIRMED" />
          <el-option label="误判放行" value="FALSE_POSITIVE" />
          <el-option label="处理失败" value="FAILED" />
          <el-option label="处理中" value="PROCESSING" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索文件名 / 转写内容"
          clearable
          style="width: 260px"
          @keyup.enter="reload"
          @clear="reload"
        >
          <template #append>
            <el-button :icon="Search" @click="reload" />
          </template>
        </el-input>
        <el-button :icon="Refresh" circle @click="reload" />
        <el-upload
          :show-file-list="false"
          :http-request="doUpload"
          multiple
          accept="audio/*,.mp3,.wav,.m4a,.mp4,.webm,.ogg,.flac,.aac,.amr,.caf,.opus,.mov"
          class="upload-btn"
        >
          <el-button type="primary" :icon="UploadFilled">上传录音</el-button>
        </el-upload>
      </div>
      <div class="toolbar-tip">
        上传后自动执行：Whisper 转写 → DFA 初筛 → AI 语义复筛；初筛与复筛均判定违规的录音进入<span class="strong">人工复检</span>。单文件 ≤ 25MB。
      </div>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never" class="mt-16">
      <el-table v-loading="loading" :data="rows" size="default" empty-text="暂无录音">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="file-name">{{ row.fileName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="100" align="center">
          <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="DFA 命中" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hitCount > 0" type="danger" size="small" effect="plain">{{ row.hitCount }} 处</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="复筛结论" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.violationTypeLabel" type="warning" size="small">{{ row.violationTypeLabel }}</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="上传时间" width="160">
          <template #default="{ row }">{{ formatTime(row.uploadTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'NEEDS_REVIEW'"
              size="small"
              link
              type="danger"
              @click="$router.push(`/review?id=${row.id}`)"
            >去复检</el-button>
            <el-button v-if="row.status === 'FAILED'" size="small" link type="warning" @click="retry(row)">
              重试
            </el-button>
            <el-popconfirm title="确认删除该录音（含文件与日志）？" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
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
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" size="62%" :title="detail ? `录音 #${detail.id} · ${detail.fileName}` : ''">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
            <el-descriptions-item label="时长">{{ formatDuration(detail.durationSeconds) }}</el-descriptions-item>
            <el-descriptions-item label="语言">{{ detail.language || '-' }}</el-descriptions-item>
            <el-descriptions-item label="文件大小">{{ formatSize(detail.fileSize) }}</el-descriptions-item>
            <el-descriptions-item label="DFA 命中">{{ detail.hitCount }} 处</el-descriptions-item>
            <el-descriptions-item label="上传时间">{{ formatTime(detail.uploadTime) }}</el-descriptions-item>
          </el-descriptions>

          <!-- AI 复筛结果 -->
          <el-card shadow="never" class="mt-16" v-if="aiResult">
            <template #header>
              <span>AI 语义复筛结果（GPT-4o mini）</span>
            </template>
            <el-descriptions :column="2" size="small">
              <el-descriptions-item label="是否违规">
                <el-tag :type="aiResult.violation ? 'danger' : 'success'" size="small">
                  {{ aiResult.violation ? '违规' : '正常' }}
                </el-tag>
                <el-tag v-if="aiResult.violationTypeLabel" type="warning" size="small" class="ml-8">
                  {{ aiResult.violationTypeLabel }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="置信度">
                {{ aiResult.confidence != null ? (aiResult.confidence * 100).toFixed(1) + '%' : '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="判定理由" :span="2">{{ aiResult.reason || '-' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 转写与命中 -->
          <el-card shadow="never" class="mt-16">
            <template #header>
              <div class="card-head">
                <span>转写文本</span>
                <div>
                  <el-button size="small" link type="primary" @click="toggleSegments">
                    {{ showSegments ? '收起分段' : '查看分段' }}
                  </el-button>
                  <el-button
                    v-if="detail.status === 'NEEDS_REVIEW'"
                    size="small"
                    type="danger"
                    @click="$router.push(`/review?id=${detail.id}`)"
                  >去人工复检</el-button>
                </div>
              </div>
            </template>
            <TranscriptViewer
              :text="detail.transcript || '（无转写文本，请检查处理日志）'"
              :hits="dfaHits"
              :target-sentence="aiResult?.targetSentence"
            />
            <el-collapse-transition>
              <div v-show="showSegments && detail.segmentsJson">
                <el-table :data="segments" size="small" max-height="260" class="mt-16">
                  <el-table-column label="起止" width="140">
                    <template #default="{ row }">{{ fmt(row.start) }} ~ {{ fmt(row.end) }}</template>
                  </el-table-column>
                  <el-table-column prop="text" label="分段文本" min-width="300" />
                </el-table>
              </div>
            </el-collapse-transition>
          </el-card>

          <!-- 复检信息 -->
          <el-card v-if="detail.reviewResult" shadow="never" class="mt-16">
            <template #header><span>人工复检</span></template>
            <el-descriptions :column="3" size="small">
              <el-descriptions-item label="结论">
                <el-tag :type="detail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'success'" size="small">
                  {{ detail.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="复检人">{{ detail.reviewer || '-' }}</el-descriptions-item>
              <el-descriptions-item label="复检时间">{{ formatTime(detail.reviewTime) }}</el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ detail.reviewComment || '-' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 处理日志 -->
          <el-card shadow="never" class="mt-16">
            <template #header><span>处理轨迹</span></template>
            <el-timeline v-if="logs.length">
              <el-timeline-item
                v-for="(l, i) in logs"
                :key="i"
                :type="l.level === 'ERROR' ? 'danger' : l.level === 'WARN' ? 'warning' : 'primary'"
                :timestamp="formatTime(l.createdAt)"
              >
                <b>{{ stageName(l.stage) }}</b> · {{ l.message }}
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无处理日志" :image-size="60" />
          </el-card>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, UploadFilled } from '@element-plus/icons-vue'
import api from '../api'
import StatusTag from '../components/StatusTag.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import { formatDuration, formatSize, formatTime, PROCESSING_STATUSES } from '../constants'

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 10
const loading = ref(false)
const statusFilter = ref('')
const keyword = ref('')

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const logs = ref([])
const showSegments = ref(false)

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
const segments = computed(() => {
  try {
    return JSON.parse(detail.value?.segmentsJson || '[]')
  } catch {
    return []
  }
})

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
function stageName(s) {
  return STAGE_NAMES[s] || s
}

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

function reload() {
  page.value = 0
  load()
}

async function doUpload({ file, onSuccess, onError }) {
  try {
    const r = await api.upload(file)
    onSuccess(r)
    ElMessage.success(`上传成功 #${r.id}，已进入稽核流水线`)
    load()
  } catch (e) {
    onError(e)
  }
}

async function openDetail(row) {
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
}

async function remove(row) {
  await api.deleteRecording(row.id)
  ElMessage.success('已删除')
  if (detail.value?.id === row.id) detailVisible.value = false
  load()
}

function toggleSegments() {
  showSegments.value = !showSegments.value
}

// 有处理中的记录时轮询刷新
let timer = null
function hasProcessing() {
  return rows.value.some((r) => PROCESSING_STATUSES.includes(r.status))
}
onMounted(() => {
  load()
  timer = setInterval(() => {
    if (hasProcessing()) load()
  }, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.toolbar-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.upload-btn {
  margin-left: auto;
}
.toolbar-tip {
  margin-top: 12px;
  color: #909399;
  font-size: 12px;
}
.strong {
  color: #f56c6c;
  font-weight: 600;
}
.mt-16 {
  margin-top: 16px;
}
.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
.file-name {
  font-weight: 500;
}
.muted {
  color: #c0c4cc;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ml-8 {
  margin-left: 8px;
}
</style>