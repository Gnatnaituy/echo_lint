<template>
  <div class="page stack">
    <!-- 顶部：队列概览 + 视图切换 -->
    <section class="panel topbar">
      <div class="row">
        <div class="seg">
          <button class="seg-item" :class="{ on: tab === 'pending' }" @click="switchTab('pending')">
            待复检
            <span class="seg-count" :class="{ hot: pendingRows.length > 0 }">{{ pendingRows.length }}</span>
          </button>
          <button class="seg-item" :class="{ on: tab === 'history' }" @click="switchTab('history')">
            已复检
            <span class="seg-count">{{ historyTotal }}</span>
          </button>
        </div>
        <span class="spacer"></span>
        <template v-if="tab === 'pending'">
          <span class="tiny dim">处理逻辑：初筛 ∩ 复筛均判违规才进入复检队列</span>
        </template>
      </div>
    </section>

    <!-- 待复检工作区 -->
    <div v-show="tab === 'pending'" class="workspace">
      <!-- 队列 -->
      <aside class="panel queue">
        <div class="queue-head">
          <div class="section-title">复检队列</div>
          <el-input v-model="queueKeyword" size="small" placeholder="筛选文件/类型" clearable class="queue-search">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="queue-list">
          <button
            v-for="(r, i) in filteredQueue"
            :key="r.id"
            class="queue-item"
            :class="{ on: current?.id === r.id }"
            :title="queueTooltip(r)"
            @click="select(r)"
          >
            <span class="queue-index num">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="queue-body">
              <span class="queue-name ellipsis">{{ r.fileName }}</span>
              <span class="queue-meta">
                <span class="dot" :style="{ background: 'var(--warn)' }"></span>
                <span class="queue-type">{{ r.aiViolationTypeLabel || '未分类' }}</span>
                <span class="queue-time num">{{ formatRelative(r.uploadTime) }}</span>
              </span>
            </span>
          </button>

          <div v-if="!filteredQueue.length" class="empty-block">
            <el-icon :size="32" class="empty-icon"><CircleCheckFilled /></el-icon>
            <div class="empty-title">{{ pendingRows.length ? '没有匹配的录音' : '队列已清空' }}</div>
            <div class="tiny dim">{{ pendingRows.length ? '试试其他关键词' : '当前没有待人工复检的录音' }}</div>
          </div>
        </div>
      </aside>

      <!-- 工作区 -->
      <section class="panel work" v-loading="detailLoading">
        <template v-if="current">
          <!-- 头部 -->
          <div class="work-head">
            <div class="min-w-0">
              <div class="work-name ellipsis">{{ current.fileName }}</div>
              <div class="work-chips">
                <span class="chip mono">#{{ current.id }}</span>
                <span class="chip">{{ formatDuration(current.durationSeconds) }}</span>
                <span class="chip danger">DFA 命中 {{ current.hitCount }} 处</span>
                <span v-if="aiResult?.violationTypeLabel" class="chip warn">AI：{{ aiResult.violationTypeLabel }}</span>
                <span class="chip num">{{ formatTime(current.uploadTime) }}</span>
              </div>
            </div>
            <div class="work-actions">
              <el-button size="small" :icon="Right" @click="autoNext">
                下一条<template v-if="remaining > 0">（剩 {{ remaining }}）</template>
              </el-button>
            </div>
          </div>

          <!-- 已出结论 -->
          <div v-if="alreadyReviewed" class="reviewed">
            <el-icon :size="38" :class="current.reviewResult === 'CONFIRMED_VIOLATION' ? 'icon-bad' : 'icon-ok'">
              <component :is="current.reviewResult === 'CONFIRMED_VIOLATION' ? 'WarningFilled' : 'CircleCheckFilled'" />
            </el-icon>
            <div class="reviewed-title">
              {{ current.reviewResult === 'CONFIRMED_VIOLATION' ? '已确认违规' : '已判定为误报' }}
            </div>
            <div class="reviewed-sub">
              结论已回馈语料库①，并{{ current.reviewResult === 'CONFIRMED_VIOLATION' ? '触发敏感词挖掘（新词默认停用待审核）' : '作为合规反例参与 AI 复筛' }}
            </div>
            <el-button type="primary" @click="autoNext">处理下一条</el-button>
          </div>

          <template v-else>
            <div class="player-row">
              <audio
                v-if="current.filePath"
                ref="audioRef"
                class="audio"
                controls
                preload="metadata"
                :src="audioSrc"
                @timeupdate="onTimeUpdate"
                @seeked="onTimeUpdate"
              ></audio>
              <div v-else class="no-audio tiny dim">该录音无可播放文件（演示数据不含音频）</div>

              <!-- 分声道试听：双轨录音可单独听坐席 / 客户 -->
              <div v-if="channelFiles" class="channel-switch">
                <button
                  v-for="opt in channelOptions"
                  :key="opt.key"
                  class="ch-btn"
                  :class="{ on: audioChannel === opt.key }"
                  @click="switchChannel(opt.key)"
                >
                  <span v-if="opt.badge" class="ch-badge sm" :class="opt.key === 'R' ? 'r' : 'l'">{{ opt.badge }}</span>
                  {{ opt.label }}
                </button>
              </div>

              <div class="player-meta mono">
                <span>{{ formatClock(currentTime) }}</span>
                <span class="dim">/ {{ formatClock(current.durationSeconds || totalDuration) }}</span>
              </div>
            </div>

            <div class="work-grid">
              <!-- 左：转写（双栏对话 / 逐句 / 全文） -->
              <div class="work-col">
                <div class="block-head">
                  <div class="seg-toggle">
                    <button
                      v-if="dialogueAvailable"
                      class="seg-btn"
                      :class="{ on: transcriptMode === 'dialogue' }"
                      @click="transcriptMode = 'dialogue'"
                    >
                      <el-icon :size="12"><Connection /></el-icon>
                      双栏对话
                    </button>
                    <button
                      class="seg-btn"
                      :class="{ on: transcriptMode === 'segment' }"
                      @click="transcriptMode = 'segment'"
                    >
                      <el-icon :size="12"><Tickets /></el-icon>
                      逐句对照
                    </button>
                    <button
                      class="seg-btn"
                      :class="{ on: transcriptMode === 'full' }"
                      @click="transcriptMode = 'full'"
                    >
                      <el-icon :size="12"><Document /></el-icon>
                      全文
                    </button>
                  </div>
                  <label v-if="transcriptMode !== 'full'" class="follow">
                    <el-switch v-model="follow" size="small" />
                    <span class="tiny dim">跟随播放</span>
                  </label>
                </div>

                <div class="transcript-scroll">
                  <SegmentTranscript
                    v-if="transcriptMode !== 'full'"
                    :text="current.transcript || ''"
                    :segments="segments"
                    :hits="dfaHits"
                    :target-sentence="aiResult?.targetSentence"
                    :current-time="currentTime"
                    :follow="follow"
                    :layout="transcriptMode === 'dialogue' ? 'dialogue' : 'single'"
                    @seek="seekTo"
                  />
                  <TranscriptViewer
                    v-else
                    :text="current.transcript || '（无转写文本）'"
                    :hits="dfaHits"
                    :target-sentence="aiResult?.targetSentence"
                  />
                </div>
              </div>

              <!-- 右：AI 判断 + 结论 -->
              <div class="work-col side">
                <div class="verdict" :class="aiResult?.violation ? 'bad' : 'ok'">
                  <div class="verdict-head">
                    <span class="verdict-badge">
                      <el-icon :size="14">
                        <component :is="aiResult?.violation ? 'WarningFilled' : 'CircleCheckFilled'" />
                      </el-icon>
                      AI 复筛：{{ aiResult?.violation ? '判定违规' : '判定正常' }}
                    </span>
                    <span v-if="aiResult?.violationTypeLabel" class="chip warn">{{ aiResult.violationTypeLabel }}</span>
                  </div>
                  <div class="conf-track">
                    <div
                      class="conf-fill"
                      :class="aiResult?.violation ? 'bad' : 'ok'"
                      :style="{ width: (aiResult?.confidence || 0) * 100 + '%' }"
                    ></div>
                  </div>
                  <div class="conf-line">
                    <span class="tiny dim">置信度</span>
                    <b class="num">{{ aiResult?.confidence != null ? Math.round(aiResult.confidence * 100) + '%' : '—' }}</b>
                  </div>
                  <div class="verdict-reason">{{ aiResult?.reason || '（无复筛理由）' }}</div>
                </div>

                <div class="form">
                  <div class="block-title">人工复检结论</div>
                  <div class="verdict-choice">
                    <button
                      class="choice"
                      :class="{ on: form.result === 'CONFIRMED_VIOLATION', bad: form.result === 'CONFIRMED_VIOLATION' }"
                      @click="form.result = 'CONFIRMED_VIOLATION'"
                    >
                      <el-icon :size="15"><WarningFilled /></el-icon>
                      确认违规
                    </button>
                    <button
                      class="choice"
                      :class="{ on: form.result === 'FALSE_POSITIVE', ok: form.result === 'FALSE_POSITIVE' }"
                      @click="form.result = 'FALSE_POSITIVE'"
                    >
                      <el-icon :size="15"><CircleCheckFilled /></el-icon>
                      判定正常
                    </button>
                  </div>

                  <div v-if="form.result === 'CONFIRMED_VIOLATION'" class="types">
                    <div class="types-label">违规类型</div>
                    <div class="types-grid">
                      <button
                        v-for="(label, code) in VIOLATION_TYPES"
                        :key="code"
                        class="type-chip"
                        :class="{ on: form.violationType === code }"
                        @click="form.violationType = code"
                      >
                        {{ label }}
                      </button>
                    </div>
                  </div>

                  <el-input
                    v-model="form.comment"
                    type="textarea"
                    :rows="3"
                    maxlength="300"
                    show-word-limit
                    placeholder="复检说明（将作为语料库的标注理由，留空则沿用 AI 判定理由）"
                  />

                  <el-button type="primary" class="submit" :loading="submitting" @click="submit">
                    {{ form.result === 'CONFIRMED_VIOLATION' ? '确认违规并回馈语料库' : '判定正常并回馈语料库' }}
                  </el-button>
                </div>
              </div>
            </div>
          </template>
        </template>

        <div v-else class="empty-block">
          <el-icon :size="34" class="empty-icon"><DocumentChecked /></el-icon>
          <div class="empty-title">选择左侧录音开始复检</div>
          <div class="tiny dim">复检结论会写入语料库，用于 AI 复筛的 few-shot 参考</div>
        </div>
      </section>
    </div>

    <!-- 已复检 -->
    <section v-show="tab === 'history'" class="panel">
      <el-table :data="historyRows" v-loading="historyLoading">
        <el-table-column label="录音" min-width="240">
          <template #default="{ row }">
            <div class="file-cell">
              <div class="file-icon"><el-icon><Microphone /></el-icon></div>
              <div class="min-w-0">
                <div class="ellipsis strong">{{ row.fileName }}</div>
                <div class="tiny dim mono">#{{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结论" width="118" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'">
              {{ row.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="112" align="center">
          <template #default="{ row }">
            <span v-if="row.violationTypeLabel" class="chip warn">{{ row.violationTypeLabel }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewComment" label="复检备注" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ dim: !row.reviewComment }">{{ row.reviewComment || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewer" label="复检人" width="100" align="center" />
        <el-table-column label="复检时间" width="160">
          <template #default="{ row }">
            <span class="num dim">{{ formatTime(row.reviewTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openHistoryDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">暂无复检记录</div>
            <div class="tiny dim">完成第一条人工复检后，这里会沉淀结论</div>
          </div>
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="historyTotal"
          :page-size="historyPageSize"
          :current-page="historyPage + 1"
          @current-change="(p) => { historyPage = p - 1; loadHistory() }"
        />
      </div>
    </section>

    <!-- 历史详情 -->
    <el-drawer v-model="historyVisible" size="700px" :with-header="false">
      <template v-if="historyDetail">
        <div class="work-head">
          <div class="min-w-0">
            <div class="work-name ellipsis">{{ historyDetail.fileName }}</div>
            <div class="work-chips">
              <span class="chip" :class="historyDetail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'">
                {{ historyDetail.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
              </span>
              <span v-if="historyDetail.violationTypeLabel" class="chip warn">{{ historyDetail.violationTypeLabel }}</span>
              <span class="chip">{{ historyDetail.reviewer || '—' }} · {{ formatTime(historyDetail.reviewTime) }}</span>
            </div>
          </div>
          <el-button :icon="Close" circle size="small" @click="historyVisible = false" />
        </div>
        <div v-if="historyDetail.reviewComment" class="comment-block">
          {{ historyDetail.reviewComment }}
        </div>
        <div class="block-title mt-14">录音转写</div>
        <TranscriptViewer :text="historyDetail.transcript" :hits="historyHits" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Right, Close } from '@element-plus/icons-vue'
import api from '../api'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import SegmentTranscript from '../components/SegmentTranscript.vue'
import { formatClock } from '../utils/segments'
import { formatDuration, formatTime, formatRelative, VIOLATION_TYPES, PROCESSING_STATUSES } from '../constants'

const route = useRoute()

const tab = ref('pending')
const pendingRows = ref([])
const current = ref(null)
const detailLoading = ref(false)
const alreadyReviewed = ref(false)
const submitting = ref(false)
const queueKeyword = ref('')

const form = ref({ result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' })

const historyRows = ref([])
const historyTotal = ref(0)
const historyPage = ref(0)
const historyPageSize = 10
const historyLoading = ref(false)
const historyVisible = ref(false)
const historyDetail = ref(null)

// 音画联动
const audioRef = ref(null)
const currentTime = ref(0)
const follow = ref(true)
const transcriptMode = ref('segment')
/** mix | L | R：双轨录音可分声道试听 */
const audioChannel = ref('mix')
const modeInitialized = ref(false)

const channelFiles = computed(() => parseJson(current.value?.channelFilesJson, null))
const dialogueAvailable = computed(() => segments.value.some((s) => s?.channel))

const channelOptions = computed(() => {
  const files = channelFiles.value || {}
  const leftName = segments.value.find((s) => s?.channel === 'L')?.speaker || '左声道'
  const rightName = segments.value.find((s) => s?.channel === 'R')?.speaker || '右声道'
  return [
    { key: 'mix', label: '混合', badge: '' },
    { key: 'L', label: files.L ? leftName : '左声道', badge: 'L' },
    { key: 'R', label: files.R ? rightName : '右声道', badge: 'R' }
  ]
})

/** 播放源：默认混合声道，可切到单独声道 */
const audioSrc = computed(() => {
  const files = channelFiles.value
  if (files && audioChannel.value !== 'mix' && files[audioChannel.value]) {
    return `/uploads/${files[audioChannel.value]}`
  }
  return current.value?.filePath ? `/uploads/${current.value.filePath}` : ''
})

function switchChannel(key) {
  if (audioChannel.value === key) return
  audioChannel.value = key
  currentTime.value = 0
}

const filteredQueue = computed(() => {
  const kw = queueKeyword.value.trim().toLowerCase()
  if (!kw) return pendingRows.value
  return pendingRows.value.filter(
    (r) =>
      r.fileName?.toLowerCase().includes(kw) ||
      (r.aiViolationTypeLabel || '').toLowerCase().includes(kw)
  )
})

const remaining = computed(() => Math.max(0, pendingRows.value.length - 1))

/** 队列行保持两行简洁，摘要与置信度放进原生 tooltip，信息不丢但不占版面 */
function queueTooltip(r) {
  const conf = r.aiConfidence != null ? Math.round(r.aiConfidence * 100) + '%' : '—'
  const lines = [`${r.aiViolationTypeLabel || '未分类'} · 置信度 ${conf}`]
  if (r.transcriptSnippet) lines.push(r.transcriptSnippet)
  return lines.join('\n')
}

const dfaHits = computed(() => parseJson(current.value?.dfaHitsJson, []))
const aiResult = computed(() => parseJson(current.value?.aiResultJson, null))
const historyHits = computed(() => parseJson(historyDetail.value?.dfaHitsJson, []))
const segments = computed(() => parseJson(current.value?.segmentsJson, []))
/** 无 durationSeconds 时用最后一句的结束时间兜底 */
const totalDuration = computed(() => {
  const last = segments.value[segments.value.length - 1]
  return last?.end ?? 0
})

function onTimeUpdate(e) {
  currentTime.value = e?.target?.currentTime || 0
}

/** 点击句子 → 跳转到对应音频时间点并播放 */
function seekTo(time) {
  if (time == null) return
  const el = audioRef.value
  currentTime.value = time
  if (!el) {
    ElMessage.info(`该录音无音频文件，句子起始时间 ${formatClock(time)}`)
    return
  }
  try {
    el.currentTime = time
  } catch {
    // 元数据未就绪时忽略，等 loadedmetadata 后可再次点击
  }
  el.play?.()?.catch?.(() => {})
}

function parseJson(raw, fallback) {
  try {
    return JSON.parse(raw || 'null') ?? fallback
  } catch {
    return fallback
  }
}

async function loadPending() {
  const data = await api.listRecordings({ statuses: ['NEEDS_REVIEW'], page: 0, size: 100 })
  pendingRows.value = data.content || []

  const stillThere = current.value && pendingRows.value.some((r) => r.id === current.value.id)
  if (!current.value || alreadyReviewed.value || !stillThere) {
    alreadyReviewed.value = false
    const target = pendingRows.value.find((r) => r.id === Number(route.query.id)) || pendingRows.value[0] || null
    if (target) await select(target)
    else current.value = null
  }
}

async function select(r) {
  current.value = r
  alreadyReviewed.value = false
  form.value = { result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' }
  // 切换录音时重置播放进度与视图模式
  currentTime.value = 0
  audioChannel.value = 'mix'
  detailLoading.value = true
  try {
    const detail = await api.recordingDetail(r.id)
    current.value = detail
    alreadyReviewed.value = detail.status !== 'NEEDS_REVIEW'
    // 首次遇到双轨录音默认用双栏对话；若当前模式不适用则回退逐句
    if (dialogueAvailable.value && !modeInitialized.value) {
      transcriptMode.value = 'dialogue'
    }
    if (transcriptMode.value === 'dialogue' && !dialogueAvailable.value) {
      transcriptMode.value = 'segment'
    }
    modeInitialized.value = true
    // 默认选中 AI 建议的违规类型，减少复检操作成本
    const suggested = parseJson(detail.aiResultJson, null)?.violationType
    if (suggested && VIOLATION_TYPES[suggested]) {
      form.value.violationType = suggested
    }
  } catch {
    // 详情加载失败时保留列表项，避免工作区空白
  } finally {
    detailLoading.value = false
  }
}

async function submit() {
  if (form.value.result === 'CONFIRMED_VIOLATION' && !form.value.violationType) {
    ElMessage.warning('请选择违规类型')
    return
  }
  submitting.value = true
  try {
    const saved = await api.review(current.value.id, {
      result: form.value.result,
      violationType: form.value.violationType,
      comment: form.value.comment
    })
    current.value = saved
    alreadyReviewed.value = true
    ElMessage.success(
      form.value.result === 'CONFIRMED_VIOLATION'
        ? '已确认违规：结论回馈语料库，并启动敏感词挖掘'
        : '已判定正常：作为合规反例回馈语料库'
    )
    await loadPending()
    if (tab.value === 'history') loadHistory()
  } finally {
    submitting.value = false
  }
}

function autoNext() {
  const list = filteredQueue.value
  const idx = list.findIndex((r) => r.id === current.value?.id)
  const next = list[idx + 1] || list[0]
  if (next && next.id !== current.value?.id) select(next)
  else loadPending()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await api.listRecordings({
      statuses: ['VIOLATION_CONFIRMED', 'FALSE_POSITIVE'],
      page: historyPage.value,
      size: historyPageSize
    })
    historyRows.value = data.content || []
    historyTotal.value = data.totalElements || 0
  } finally {
    historyLoading.value = false
  }
}

async function openHistoryDetail(row) {
  historyDetail.value = await api.recordingDetail(row.id)
  historyVisible.value = true
}

function switchTab(name) {
  tab.value = name
  if (name === 'history') loadHistory()
  else loadPending()
}

let timer = null
onMounted(() => {
  loadPending()
  loadHistory()
  timer = setInterval(() => {
    if (tab.value === 'pending' && pendingRows.value.some((r) => PROCESSING_STATUSES.includes(r.status))) {
      loadPending()
    }
  }, 5000)
})
onUnmounted(() => clearInterval(timer))

watch(
  () => route.query.id,
  () => {
    if (tab.value === 'pending') loadPending()
  }
)
</script>

<style scoped>
.topbar {
  padding: 12px 18px;
}

/* 分段控件 */
.seg {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 10px;
  padding: 3px;
  gap: 3px;
}
.seg-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
}
.seg-item.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.seg-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--ink-200);
  color: var(--ink-500);
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.seg-count.hot {
  background: var(--danger);
  color: #fff;
}

/* 工作区 */
.workspace {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
@media (max-width: 1320px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  /* 窄屏（含侧边栏浏览器面板）上下堆叠时，队列别把工作区挤出首屏 */
  .queue {
    max-height: 38vh;
  }
}

/* 队列 */
.queue {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 190px);
}
.queue-head {
  padding: 13px 14px 11px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.queue-search {
  width: 100%;
}
.queue-list {
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.queue-item {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  background: #fff;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.queue-item:hover {
  background: var(--ink-50);
}
.queue-item.on {
  background: var(--brand-50);
  border-color: var(--brand-200);
}
.queue-index {
  flex: none;
  width: 18px;
  font-size: 11px;
  color: var(--ink-400);
  font-weight: 600;
}
.queue-item.on .queue-index {
  color: var(--brand-500);
}
.queue-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.queue-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-700);
  line-height: 1.45;
}
.queue-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  line-height: 1.4;
}
.queue-type {
  color: #b06a06;
  font-weight: 500;
}
.queue-time {
  color: var(--el-text-color-placeholder);
}
.queue-time::before {
  content: "·";
  margin-right: 6px;
  color: var(--ink-300);
}
/* 工作区主体 */
.work {
  padding: 16px 18px 20px;
  min-height: 420px;
}
.work-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.work-name {
  font-size: 15.5px;
  font-weight: 600;
  color: var(--ink-800);
  max-width: 620px;
}
.work-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.work-actions {
  flex: none;
}
.player-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 14px 0 4px;
}
.audio {
  flex: 1;
  min-width: 0;
  height: 38px;
}
.no-audio {
  flex: 1;
  height: 38px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  border: 1px dashed var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
}
.player-meta {
  flex: none;
  font-size: 12px;
  color: var(--brand-600);
  display: inline-flex;
  gap: 5px;
  align-items: baseline;
}
.player-meta .dim {
  font-size: 11px;
}

/* 分声道试听切换 */
.channel-switch {
  flex: none;
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.ch-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
}
.ch-btn.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.ch-badge.sm {
  width: 16px;
  height: 16px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  font-family: var(--font-mono);
  color: #fff;
}
.ch-badge.sm.l {
  background: var(--brand-500);
}
.ch-badge.sm.r {
  background: #64748b;
}

/* 逐句 / 全文切换 */
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 11px;
}
.seg-toggle {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.seg-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
}
.seg-btn.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.follow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.work-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.95fr);
  gap: 18px;
  margin-top: 12px;
}
@media (max-width: 1180px) {
  .work-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.work-col {
  min-width: 0;
}
.transcript-scroll {
  max-height: 470px;
  overflow-y: auto;
  padding-right: 4px;
}

.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 11px;
}
.mt-14 {
  margin-top: 14px;
}

/* AI 判断 */
.verdict {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 12px 14px;
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
  flex-wrap: wrap;
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
.conf-track {
  height: 6px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.07);
  margin: 10px 0 5px;
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
.conf-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.conf-line b {
  color: var(--ink-700);
}
.verdict-reason {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

/* 结论表单 */
.form {
  margin-top: 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 13px 14px 15px;
}
.verdict-choice {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  margin-bottom: 13px;
}
.choice {
  height: 40px;
  border-radius: var(--r-sm);
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.16s ease;
}
.choice:hover {
  border-color: var(--brand-300);
}
.choice.on.bad {
  background: var(--danger-soft);
  border-color: #f0a9a9;
  color: #c92c2c;
}
.choice.on.ok {
  background: var(--ok-soft);
  border-color: #a9d9bc;
  color: #12823b;
}

.types {
  margin-bottom: 13px;
}
.types-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 7px;
}
.types-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.type-chip {
  height: 26px;
  padding: 0 11px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.type-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.type-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
}
.submit {
  width: 100%;
  margin-top: 13px;
}

/* 已出结论面板 */
.reviewed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 46px 20px;
  text-align: center;
}
.icon-bad {
  color: var(--danger);
}
.icon-ok {
  color: var(--ok);
}
.reviewed-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-800);
}
.reviewed-sub {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  max-width: 460px;
  line-height: 1.7;
  margin-bottom: 6px;
}

/* 历史 */
.file-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.file-icon {
  width: 30px;
  height: 30px;
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
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}
.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  margin: 8px 0 4px;
  font-weight: 600;
  color: var(--ink-600);
}
.comment-block {
  margin-top: 14px;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
</style>
