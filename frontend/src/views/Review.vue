<template>
  <div>
    <!-- 待复检 / 已复检 -->
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane name="pending">
          <template #label>待复检 <el-badge v-if="pendingTotal > 0" :value="pendingTotal" type="danger" /></template>
          <div class="review-layout">
            <!-- 待复检列表 -->
            <div class="review-list">
              <el-scrollbar height="560px">
                <div
                  v-for="r in pendingRows"
                  :key="r.id"
                  class="review-item"
                  :class="{ active: current?.id === r.id }"
                  @click="select(r)"
                >
                  <div class="review-item-head">
                    <b>#{{ r.id }}</b>
                    <el-tag type="warning" size="small" effect="plain">{{ r.violationTypeLabel || '未分类' }}</el-tag>
                  </div>
                  <div class="review-item-file">{{ r.fileName }}</div>
                  <div class="review-item-meta">{{ formatTime(r.uploadTime) }} · {{ formatDuration(r.durationSeconds) }}</div>
                  <div class="review-item-snippet">{{ r.transcriptSnippet || '（无转写）' }}</div>
                </div>
                <el-empty v-if="!pendingRows.length" description="没有待复检录音 🎉" :image-size="80" />
              </el-scrollbar>
            </div>

            <!-- 复检工作区 -->
            <div class="review-detail" v-loading="detailLoading">
              <template v-if="current">
                <!-- 复检通过后展示结论并以原句定位 -->
                <div v-if="alreadyReviewed" class="reviewed-banner">
                  <el-result
                    :icon="current.reviewResult === 'CONFIRMED_VIOLATION' ? 'error' : 'success'"
                    :title="current.reviewResult === 'CONFIRMED_VIOLATION' ? '已确认违规' : '已判定为误报'"
                    :sub-title="current.reviewComment || '（无备注）'"
                  >
                    <template #extra>
                      <el-button @click="autoNext">下一条 ({{ pendingRows.length }})</el-button>
                    </template>
                  </el-result>
                </div>

                <template v-else>
                  <el-alert
                    type="error"
                    :closable="false"
                    class="mb-12"
                  >
                    <template #title>
                      该录音通过 <b>DFA 初筛命中 {{ current.hitCount }} 处</b>，且 <b>AI 复筛判定违规</b>，需要您人工复核
                    </template>
                  </el-alert>

                  <!-- AI 判断 -->
                  <el-card shadow="never" class="mb-12">
                    <template #header><span>AI 复筛判断（GPT-4o mini）</span></template>
                    <el-descriptions :column="2" size="small">
                      <el-descriptions-item label="结论">
                        <el-tag type="danger" size="small">{{ aiResult?.violationTypeLabel || '违规' }}</el-tag>
                        <span class="ml-8 muted">置信度 {{ aiResult?.confidence != null ? (aiResult.confidence * 100).toFixed(1) + '%' : '-' }}</span>
                      </el-descriptions-item>
                      <el-descriptions-item label="判违规原句">
                        {{ aiResult?.targetSentence || '-' }}
                      </el-descriptions-item>
                      <el-descriptions-item label="理由" :span="2">{{ aiResult?.reason || '-' }}</el-descriptions-item>
                    </el-descriptions>
                  </el-card>

                  <!-- 转写 -->
                  <el-card shadow="never" class="mb-12">
                    <template #header><span>录音转写文本（命中词已标红）</span></template>
                    <TranscriptViewer :text="current.transcript" :hits="dfaHits" :target-sentence="aiResult?.targetSentence" />
                  </el-card>

                  <!-- 复检表单 -->
                  <el-card shadow="never">
                    <template #header><span>复检结论</span></template>
                    <el-form label-width="90px" class="review-form">
                      <el-form-item label="结论">
                        <el-radio-group v-model="form.result">
                          <el-radio-button value="CONFIRMED_VIOLATION">确认违规</el-radio-button>
                          <el-radio-button value="FALSE_POSITIVE">判定正常（误报）</el-radio-button>
                        </el-radio-group>
                      </el-form-item>
                      <el-form-item v-if="form.result === 'CONFIRMED_VIOLATION'" label="违规类型">
                        <el-select v-model="form.violationType" placeholder="选择违规类型" style="width: 220px">
                          <el-option v-for="(label, code) in VIOLATION_TYPES" :key="code" :label="label" :value="code" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="备注">
                        <el-input
                          v-model="form.comment"
                          type="textarea"
                          :rows="2"
                          placeholder="复检说明（将作为语料库标注理由）"
                        />
                      </el-form-item>
                      <el-form-item>
                        <el-button type="primary" :loading="submitting" @click="submit">
                          {{ form.result === 'CONFIRMED_VIOLATION' ? '确认违规并回馈语料' : '判定正常并回馈语料' }}
                        </el-button>
                        <el-button @click="autoNext">跳过</el-button>
                      </el-form-item>
                      <div class="form-tip">
                        提示：确认违规后，系统会扣留该录音进入语料库供复筛 few-shot 参考，并自动挖掘新敏感词（停用待审核）；判定正常会将其作为合规反例入库。
                      </div>
                    </el-form>
                  </el-card>
                </template>
              </template>
              <el-empty v-else description="在左侧选择一条待复检录音，或暂无待复检" :image-size="100" />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="history">
          <template #label>已复检</template>
          <el-table :data="historyRows" size="default" empty-text="暂无复检记录">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
            <el-table-column label="复检结论" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="row.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'success'" size="small">
                  {{ row.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110" align="center">
              <template #default="{ row }">{{ row.violationTypeLabel || '-' }}</template>
            </el-table-column>
            <el-table-column prop="reviewComment" label="备注" min-width="180" show-overflow-tooltip />
            <el-table-column prop="reviewer" label="复检人" width="100" align="center" />
            <el-table-column label="复检时间" width="160">
              <template #default="{ row }">{{ formatTime(row.reviewTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openHistoryDetail(row)">详情</el-button>
              </template>
            </el-table-column>
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
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 已复检详情 -->
    <el-drawer v-model="historyVisible" size="60%" title="复检详情">
      <template v-if="historyDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="录音">{{ historyDetail.fileName }}</el-descriptions-item>
          <el-descriptions-item label="结论">
            <el-tag :type="historyDetail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'success'" size="small">
              {{ historyDetail.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="违规类型">{{ historyDetail.violationTypeLabel || '-' }}</el-descriptions-item>
          <el-descriptions-item label="复检人/时间">{{ historyDetail.reviewer }} · {{ formatTime(historyDetail.reviewTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ historyDetail.reviewComment || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-card shadow="never" class="mt-16">
          <template #header><span>转写文本</span></template>
          <TranscriptViewer :text="historyDetail.transcript" :hits="JSON.parse(historyDetail.dfaHitsJson || '[]')" />
        </el-card>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import { formatTime, formatDuration, VIOLATION_TYPES, PROCESSING_STATUSES } from '../constants'

const route = useRoute()
const tab = ref('pending')

const pendingRows = ref([])
const pendingTotal = ref(0)
const current = ref(null)
const detailLoading = ref(false)
const alreadyReviewed = ref(false)
const submitting = ref(false)

const form = ref({ result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' })

const historyRows = ref([])
const historyTotal = ref(0)
const historyPage = ref(0)
const historyPageSize = 10
const historyVisible = ref(false)
const historyDetail = ref(null)

const dfaHits = computed(() => {
  try {
    return JSON.parse(current.value?.dfaHitsJson || '[]')
  } catch {
    return []
  }
})
const aiResult = computed(() => {
  try {
    return JSON.parse(current.value?.aiResultJson || 'null')
  } catch {
    return null
  }
})

async function loadPending() {
  const data = await api.listRecordings({ statuses: ['NEEDS_REVIEW'], page: 0, size: 100 })
  pendingRows.value = data.content || []
  pendingTotal.value = data.totalElements || 0
  // 保留当前选中；若无选中或已被处理则自动选择第一条
  if (!current.value || alreadyReviewed.value || !pendingRows.value.some((r) => r.id === current.value.id)) {
    alreadyReviewed.value = false
    const target =
      pendingRows.value.find((r) => r.id === Number(route.query.id)) || pendingRows.value[0] || null
    if (target) select(target)
    else current.value = null
  }
}

async function select(r) {
  current.value = r
  alreadyReviewed.value = false
  form.value = { result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' }
  detailLoading.value = true
  try {
    const detail = await api.recordingDetail(r.id)
    current.value = detail
    alreadyReviewed.value = detail.status !== 'NEEDS_REVIEW'
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
    alreadyReviewed.value = true
    current.value = saved
    ElMessage.success(
      form.value.result === 'CONFIRMED_VIOLATION' ? '已确认违规，结论已回馈语料并启动词典挖掘' : '已判定正常，反例已回馈语料'
    )
    await loadPending()
  } finally {
    submitting.value = false
  }
}

function autoNext() {
  const idx = pendingRows.value.findIndex((r) => r.id === current.value?.id)
  const next = pendingRows.value[idx + 1] || pendingRows.value[0]
  if (next) select(next)
  else {
    current.value = null
    alreadyReviewed.value = false
    loadPending()
  }
}

async function loadHistory() {
  const data = await api.listRecordings({
    statuses: ['VIOLATION_CONFIRMED', 'FALSE_POSITIVE'],
    page: historyPage.value,
    size: historyPageSize
  })
  historyRows.value = data.content || []
  historyTotal.value = data.totalElements || 0
}

async function openHistoryDetail(row) {
  historyDetail.value = await api.recordingDetail(row.id)
  historyVisible.value = true
}

let timer = null
onMounted(() => {
  loadPending()
  loadHistory()
  timer = setInterval(() => {
    if (pendingRows.value.some((r) => PROCESSING_STATUSES.includes(r.status))) loadPending()
  }, 4000)
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
.review-layout {
  display: flex;
  gap: 16px;
  min-height: 560px;
}
.review-list {
  width: 340px;
  flex-shrink: 0;
  border-right: 1px solid #ebeef5;
  padding-right: 4px;
}
.review-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid transparent;
}
.review-item:hover {
  background: #f5f7fa;
}
.review-item.active {
  background: #ecf5ff;
  border-color: #a0cfff;
}
.review-item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.review-item-file {
  font-size: 13px;
  color: #303133;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.review-item-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.review-item-snippet {
  font-size: 12px;
  color: #606266;
  margin-top: 6px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.review-detail {
  flex: 1;
  min-width: 0;
}
.mb-12 {
  margin-bottom: 12px;
}
.ml-8 {
  margin-left: 8px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.form-tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
}
.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
.reviewed-banner {
  padding-top: 40px;
}
.mt-16 {
  margin-top: 16px;
}
</style>