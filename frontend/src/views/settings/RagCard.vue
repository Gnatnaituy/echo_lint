<template>
  <!-- ⑤ RAG 检索增强（ADMIN）：embedding 复用 AI 供应商；检索参数、索引统计与效果评测 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.rag.title') }}</div>
      <div class="provider-head">
        <span class="tiny dim">{{ t('settings.rag.subtitle') }}</span>
        <el-button size="small" :loading="ragLoading" @click="loadRag">{{
          t('common.refresh')
        }}</el-button>
      </div>
    </div>
    <div v-loading="ragLoading" class="panel-body">
      <!-- 接口未上线 / 无权限（403、404）时降级为可重试提示，不影响其他设置 -->
      <div v-if="ragError" class="notify-fallback">
        <el-icon :size="16"><WarningFilled /></el-icon>
        <div class="notify-fallback-text">
          <div class="strong">{{ t('settings.rag.unavailable') }}</div>
          <div class="tiny dim">{{ ragError }}</div>
        </div>
        <el-button size="small" @click="loadRag">{{ t('common.retry') }}</el-button>
      </div>

      <template v-else-if="rag">
        <!-- 总开关 -->
        <div class="rag-switch-row">
          <div class="rag-switch-main">
            <div class="rag-switch-line">
              <span class="strong">{{ t('settings.rag.enableLabel') }}</span>
              <span class="chip" :class="ragForm.enabled ? 'ok' : 'info'">
                {{ ragForm.enabled ? t('settings.state.enabled') : t('settings.state.disabled') }}
              </span>
              <span v-if="rag.source" class="chip info">{{
                t('settings.rag.source', { source: ragSourceLabel(rag.source) })
              }}</span>
            </div>
            <div class="tiny dim rag-note">
              {{ t('settings.rag.enableNoteA') }}
              {{ t('settings.rag.enableNoteB') }}
            </div>
          </div>
          <el-switch
            v-model="ragForm.enabled"
            :loading="ragEnabledSaving"
            :disabled="ragEnabledSaving"
            @change="saveRagEnabled"
          />
        </div>

        <!-- Embedding 配置：状态行 + 供应商卡片网格 + 模型 + 只读运行信息 + 保存 -->
        <div class="rag-section-head">
          <div class="block-title">{{ t('settings.rag.embeddingTitle') }}</div>
          <span class="tiny dim">{{ t('settings.rag.embeddingHint') }}</span>
        </div>

        <ConfigStatusBar
          :label="t('settings.rag.embeddingActive')"
          :provider="rag.embedding.providerLabel"
          :model="rag.embedding.model"
          :tone="embeddingKeyChip(rag.embedding.apiKeyConfigured).type === 'ok' ? 'ok' : 'warn'"
          :icon="Connection"
        >
          <template #chips>
            <span class="chip" :class="embeddingKeyChip(rag.embedding.apiKeyConfigured).type">
              {{ embeddingKeyChip(rag.embedding.apiKeyConfigured).label }}
            </span>
            <span v-if="rag.embedding.source" class="chip info">
              {{ t('settings.rag.source', { source: ragSourceLabel(rag.embedding.source) }) }}
            </span>
          </template>
          <template #actions>
            <el-button
              size="small"
              :loading="ragTesting"
              :disabled="ragTesting"
              @click="testRagEmbedding"
            >
              <el-icon v-if="!ragTesting" class="el-icon--left"><Connection /></el-icon>
              {{ t('settings.rag.testConnection') }}
            </el-button>
          </template>
        </ConfigStatusBar>

        <div
          v-if="ragTestResult"
          class="test-result"
          :class="ragTestResult.ok === true ? 'ok' : 'bad'"
        >
          <el-icon :size="16">
            <component :is="ragTestResult.ok === true ? CircleCheckFilled : CircleCloseFilled" />
          </el-icon>
          <div class="test-text">
            <div class="test-head">
              <b>{{
                ragTestResult.ok === true ? t('settings.rag.testOk') : t('settings.rag.testFail')
              }}</b>
              <span v-if="ragTestResult.providerLabel || ragTestResult.model" class="mono dim">
                {{ ragTestResult.providerLabel || '—' }} · {{ ragTestResult.model || '—' }}
              </span>
              <span v-if="ragTestResult.dimensions != null" class="num dim">
                {{ t('settings.rag.dimensionValue', { dimensions: ragTestResult.dimensions }) }}
              </span>
              <span v-if="ragTestResult.latencyMs != null" class="num dim">
                {{ t('settings.test.latency', { ms: ragTestResult.latencyMs }) }}
              </span>
            </div>
            <div class="test-msg">
              {{
                ragTestResult.message ||
                (ragTestResult.ok === true
                  ? t('settings.rag.testSuccess')
                  : t('settings.rag.testFailHint'))
              }}
            </div>
          </div>
        </div>

        <!-- Embedding 供应商：与复筛模型 / 语音转写同一套卡片网格 -->
        <div class="block-head">
          <div class="block-title">{{ t('settings.rag.chooseEmbeddingProvider') }}</div>
          <span class="tiny dim">{{ t('settings.rag.chooseEmbeddingProviderHint') }}</span>
        </div>
        <ProviderCardGrid
          :cards="embeddingCards"
          :loading="providersLoading"
          :error="providersError"
          editable
          :reason-text="embeddingReasonText"
          @select="selectEmbeddingProvider"
          @edit="openEmbeddingProviderEdit"
          @retry="loadProviders"
        />

        <!-- Embedding 模型 + 运行时只读信息（路径 / 维度 / 超时） -->
        <div class="embedding-form">
          <div class="eval-field embedding-field">
            <label class="eval-label">{{ t('settings.rag.embeddingModel') }}</label>
            <el-select
              v-model="embeddingForm.model"
              filterable
              allow-create
              default-first-option
              :placeholder="t('settings.rag.embeddingModelPlaceholder')"
            >
              <el-option v-for="m in embeddingModelOptions" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="embedding-spec">
            <span class="tiny dim">
              {{ t('settings.rag.requestPath') }}
              <b class="mono">{{ rag.embedding.path || '—' }}</b>
            </span>
            <span class="tiny dim">
              {{ t('settings.rag.dimensions') }}
              <b class="num">{{ rag.embedding.dimensions ?? '—' }}</b>
            </span>
            <span class="tiny dim">
              {{ t('settings.rag.timeout', { seconds: rag.embedding.timeoutSeconds ?? '—' }) }}
            </span>
          </div>
        </div>

        <SettingsActionBar
          :hint="embeddingHint"
          :hint-tone="embeddingSaveBlocked ? 'warn' : 'dim'"
          :save-label="t('settings.rag.embeddingSave')"
          :save-loading="ragEmbeddingSaving"
          :save-disabled="!embeddingDirty || embeddingSaveBlocked"
          @save="saveEmbedding"
        />

        <!-- embedding 变更后：提示并复用现有重建索引入口 -->
        <div v-if="embeddingChanged" class="embedding-changed" :class="{ off: !ragForm.enabled }">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="embedding-changed-text">
            <div class="strong">
              {{
                t(
                  ragForm.enabled
                    ? 'settings.rag.embeddingChangedTitle'
                    : 'settings.rag.embeddingChangedTitleDisabled'
                )
              }}
            </div>
            <div class="tiny">
              {{
                t(
                  ragForm.enabled
                    ? 'settings.rag.embeddingChangedNote'
                    : 'settings.rag.embeddingChangedNoteDisabled'
                )
              }}
            </div>
          </div>
          <el-button
            size="small"
            type="warning"
            plain
            :loading="ragReindexing === 'all'"
            @click="reindexForEmbeddingChange"
          >
            {{ t('settings.rag.embeddingReindex') }}
          </el-button>
        </div>

        <!-- 检索参数 -->
        <div class="rag-section-head">
          <div class="block-title">{{ t('settings.rag.paramsTitle') }}</div>
          <span class="tiny dim">{{ t('settings.rag.paramsNote') }}</span>
        </div>
        <div class="eval-form">
          <div class="eval-field rag-field">
            <label class="eval-label">{{ t('settings.rag.topK') }}</label>
            <el-input-number
              v-model="ragForm.topK"
              :min="1"
              :max="10"
              :precision="0"
              controls-position="right"
              :disabled="ragSaving"
              style="width: 100%"
            />
          </div>
          <div class="eval-field rag-field">
            <label class="eval-label">{{ t('settings.rag.minScore') }}</label>
            <el-input-number
              v-model="ragForm.minScore"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              controls-position="right"
              :disabled="ragSaving"
              style="width: 100%"
            />
          </div>
          <div class="eval-field rag-field">
            <label class="eval-label">{{ t('settings.rag.maxContextChars') }}</label>
            <el-input-number
              v-model="ragForm.maxContextChars"
              :min="500"
              :max="8000"
              :step="100"
              :precision="0"
              controls-position="right"
              :disabled="ragSaving"
              style="width: 100%"
            />
          </div>
          <div class="eval-field rag-field">
            <label class="eval-label">{{ t('settings.rag.corpusExamples') }}</label>
            <el-input-number
              v-model="ragForm.corpusExamples"
              :min="0"
              :max="10"
              :precision="0"
              controls-position="right"
              :disabled="ragSaving"
              style="width: 100%"
            />
          </div>
          <div class="eval-actions">
            <el-button
              type="primary"
              :loading="ragSaving"
              :disabled="ragSaving"
              @click="saveRagSettings"
            >
              {{ t('settings.rag.save') }}
            </el-button>
          </div>
        </div>

        <!-- 索引统计 -->
        <div class="rag-section-head">
          <div class="block-title">{{ t('settings.rag.indexTitle') }}</div>
          <span class="tiny dim">
            {{
              t('settings.rag.lastReindex', {
                time: rag.index.lastReindexAt ? formatTime(rag.index.lastReindexAt) : '—'
              })
            }}
          </span>
        </div>
        <div class="notify-grid">
          <div class="notify-item">
            <div class="notify-label">{{ t('settings.rag.knowledgeReady') }}</div>
            <div class="notify-value num">
              {{ readyCountText(rag.index.knowledgeReady, rag.index.knowledgeTotal) }}
            </div>
            <div class="notify-sub tiny dim">
              {{
                t('settings.rag.knowledgePendingFailed', {
                  pending: rag.index.knowledgePending ?? '—',
                  failed: rag.index.knowledgeFailed ?? '—'
                })
              }}
            </div>
          </div>
          <div class="notify-item">
            <div class="notify-label">{{ t('settings.rag.corpusReady') }}</div>
            <div class="notify-value num">
              {{ readyCountText(rag.index.corpusReady, rag.index.corpusTotal) }}
            </div>
            <div class="notify-sub tiny dim">{{ t('settings.rag.corpusReadyNote') }}</div>
          </div>
          <div class="notify-item">
            <div class="notify-label">{{ t('settings.rag.vectorCapability') }}</div>
            <div class="notify-value">
              <span class="chip" :class="vectorState.type">{{ vectorState.label }}</span>
            </div>
            <div class="notify-sub tiny dim">{{ vectorState.note }}</div>
          </div>
        </div>

        <el-alert
          v-if="rag.index.vectorAvailable === false"
          class="rag-alert"
          type="warning"
          :closable="false"
          show-icon
          :title="t('settings.rag.vectorAlert')"
        />

        <div class="rag-actions">
          <el-button
            :loading="ragReindexing === 'knowledge'"
            :disabled="ragReindexing !== null"
            @click="runReindex('knowledge')"
          >
            {{ t('settings.rag.reindexKnowledge') }}
          </el-button>
          <el-button
            :loading="ragReindexing === 'corpus'"
            :disabled="ragReindexing !== null"
            @click="runReindex('corpus')"
          >
            {{ t('settings.rag.reindexCorpus') }}
          </el-button>
          <el-button
            type="warning"
            plain
            :loading="ragReindexing === 'all'"
            :disabled="ragReindexing !== null"
            @click="runReindex('all')"
          >
            {{ t('settings.rag.reindexAll') }}
          </el-button>
          <span class="tiny dim">{{ t('settings.rag.reindexNote') }}</span>
        </div>

        <!-- 效果评测 -->
        <div class="rag-section-head">
          <div class="block-title">{{ t('settings.rag.evalTitle') }}</div>
          <span class="tiny dim">{{ t('settings.rag.evalNote') }}</span>
        </div>
        <div class="eval-form">
          <div class="eval-field eval-size">
            <label class="eval-label">{{ t('settings.rag.evalSampleSize') }}</label>
            <el-input-number
              v-model="ragSampleSize"
              :min="5"
              :max="20"
              :precision="0"
              :disabled="ragEvaluating"
              controls-position="right"
              style="width: 100%"
            />
          </div>
          <div class="eval-actions">
            <el-button
              type="primary"
              :loading="ragEvaluating"
              :disabled="ragEvaluating"
              @click="runRagEvaluation"
            >
              <el-icon v-if="!ragEvaluating" class="el-icon--left"><DataAnalysis /></el-icon>
              {{ t('settings.rag.evalRun') }}
            </el-button>
            <span class="tiny dim">{{ t('settings.rag.evalCostHint') }}</span>
          </div>
        </div>

        <div v-if="ragEvaluating" class="eval-running tiny dim">
          <el-icon class="is-loading"><Loading /></el-icon>
          {{ t('settings.rag.evalRunning') }}
        </div>

        <div v-if="ragEvaluationError" class="eval-error">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="eval-error-text">
            <div class="strong">{{ t('settings.eval.incomplete') }}</div>
            <div class="tiny dim">{{ ragEvaluationError }}</div>
          </div>
          <el-button size="small" :disabled="ragEvaluating" @click="runRagEvaluation">{{
            t('common.retry')
          }}</el-button>
        </div>

        <template v-if="ragEvaluation">
          <div class="eval-kpis">
            <div v-for="row in ragEvalRows" :key="row.key" class="eval-kpi">
              <div class="ab-label">{{ row.label }}</div>
              <div class="ab-value num">{{ formatRatioPercent(row.rag) }}</div>
              <div class="ab-sub tiny dim">
                {{
                  t('settings.rag.baselineDelta', {
                    baseline: formatRatioPercent(row.baseline),
                    delta: row.deltaText
                  })
                }}
              </div>
            </div>
            <div class="eval-kpi">
              <div class="ab-label">{{ t('settings.eval.sampleSize') }}</div>
              <div class="ab-value num">{{ ragEvaluation.sampleSize ?? '—' }}</div>
              <div class="ab-sub tiny dim">
                {{ t('settings.rag.evalModel', { model: ragEvaluation.model || '—' }) }}
              </div>
            </div>
          </div>

          <div class="rag-eval-meta tiny dim">
            {{
              t('settings.rag.evalFailed', {
                baseline: ragEvaluation.baseline.failed ?? '—',
                rag: ragEvaluation.rag.failed ?? '—'
              })
            }}
            <span class="sep">·</span>
            {{
              t('settings.rag.evalCorrect', {
                baseline: ragEvaluation.baseline.correct ?? '—',
                rag: ragEvaluation.rag.correct ?? '—'
              })
            }}
          </div>

          <div class="rag-section-head">
            <div class="block-title">{{ t('settings.rag.evalCasesTitle') }}</div>
            <span class="tiny dim">{{
              t('settings.rag.casesCount', { count: ragEvaluation.cases.length })
            }}</span>
          </div>
          <div class="table-bleed">
            <el-table :data="ragEvaluation.cases" size="small" max-height="380">
              <el-table-column label="ID" width="76">
                <template #default="{ row }">
                  <span class="mono">{{ row.id ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.rag.colLabel')" width="96" align="center">
                <template #default="{ row }">
                  <span class="chip" :class="evaluationLabelMeta(row.label).type">
                    {{ evaluationLabelMeta(row.label).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('settings.rag.colBaselinePrediction')"
                width="96"
                align="center"
              >
                <template #default="{ row }">
                  <span class="chip" :class="evaluationLabelMeta(row.baselinePrediction).type">
                    {{ evaluationLabelMeta(row.baselinePrediction).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('settings.rag.colRagPrediction')"
                width="96"
                align="center"
              >
                <template #default="{ row }">
                  <span class="chip" :class="evaluationLabelMeta(row.ragPrediction).type">
                    {{ evaluationLabelMeta(row.ragPrediction).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.rag.colConfidence')" width="166" align="right">
                <template #default="{ row }">
                  <span class="num tiny">
                    {{ formatRatioPercent(row.baselineConfidence) }} →
                    {{ formatRatioPercent(row.ragConfidence) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.rag.colRefs')" min-width="150">
                <template #default="{ row }">
                  <span v-if="row.refs.length" class="tiny">{{
                    row.refs.join(t('settings.rag.refSeparator'))
                  }}</span>
                  <span v-else class="dim">—</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.rag.colError')" min-width="140">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="row.error"
                    :content="row.error"
                    placement="top"
                    :show-after="200"
                    :popper-style="{ maxWidth: '460px', wordBreak: 'break-all' }"
                  >
                    <span class="eval-reason err">{{ row.error }}</span>
                  </el-tooltip>
                  <span v-else class="dim">—</span>
                </template>
              </el-table-column>
              <template #empty>
                <div class="empty-block">
                  <div class="empty-title">{{ t('settings.rag.casesEmpty') }}</div>
                  <div class="tiny dim">{{ t('settings.rag.casesEmptyHint') }}</div>
                </div>
              </template>
            </el-table>
          </div>
        </template>
        <div v-else-if="!ragEvaluating && !ragEvaluationError" class="eval-empty tiny dim">
          {{ t('settings.rag.evalEmpty') }}
        </div>
      </template>
    </div>
  </section>

  <!-- 供应商编辑（复筛页复用同一表单）：仅 ADMIN 渲染并按需异步加载 -->
  <ProviderFormDialog
    v-if="canWrite"
    ref="embeddingFormDialog"
    @saved="refreshEmbeddingProviders"
  />
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import { formatTime } from '../../constants'
import { evaluationLabelMeta, formatRatioPercent } from '../../utils/evaluation'
import { mergeScreeningProviders } from '../../utils/providers'
import { adminErrorMessage } from './adminErrors'
import { useSettingsContext } from './context'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Connection,
  DataAnalysis,
  Loading,
  WarningFilled
} from '../../components/icons.js'
import ConfigStatusBar from './ConfigStatusBar.vue'
import ProviderCardGrid from './ProviderCardGrid.vue'
import SettingsActionBar from './SettingsActionBar.vue'

const { t } = useI18n()
const { canWrite, registry } = useSettingsContext()
const { providers, providersLoading, providersError, load: loadProviders } = registry

// 供应商编辑弹窗：ADMIN 才渲染并按需加载（与复筛页复用同一表单）
const ProviderFormDialog = defineAsyncComponent(() => import('./ProviderFormDialog.vue'))

const embeddingFormDialog = ref(null)

// RAG 检索增强（仅 ADMIN）：embedding 复用 AI 供应商注册表；索引重建与评测均为 ADMIN 接口
const rag = ref(null)
const ragLoading = ref(false)
const ragError = ref('')
const ragSaving = ref(false)
const ragEnabledSaving = ref(false)
const ragTesting = ref(false)
const ragTestResult = ref(null)
/** 正在重建的范围：'knowledge' | 'corpus' | 'all' | null */
const ragReindexing = ref(null)
const ragEvaluating = ref(false)
const ragEvaluation = ref(null)
const ragEvaluationError = ref('')
const ragSampleSize = ref(10)

// Embedding 供应商 / 模型编辑态（保存时 PUT embedding:{provider,model}）
const embeddingForm = reactive({ providerId: '', model: '' })
const ragEmbeddingSaving = ref(false)
/** 保存后后端回 embeddingChanged=true：提示历史索引需要重建 */
const embeddingChanged = ref(false)

/** 与 application.yml 默认值一致：仅作编辑控件初值，保存时整体回写后端 */
const RAG_DEFAULT_RETRIEVAL = { topK: 4, minScore: 0.25, maxContextChars: 2400, corpusExamples: 5 }
const ragForm = reactive({ enabled: false, ...RAG_DEFAULT_RETRIEVAL })

/** 向量能力状态：字段缺失显示未知，不臆造可用性 */
const vectorState = computed(() => {
  const available = rag.value?.index?.vectorAvailable
  if (available === true) {
    return {
      label: t('settings.state.available'),
      type: 'ok',
      note: t('settings.rag.vectorAvailableNote')
    }
  }
  if (available === false) {
    return {
      label: t('settings.state.unavailable'),
      type: 'warn',
      note: t('settings.rag.vectorUnavailableNote')
    }
  }
  return {
    label: t('common.unknown'),
    type: 'info',
    note: t('settings.rag.vectorUnknownNote')
  }
})

/** 评测三项指标：RAG 为主值，附基线值与 delta（delta 缺失时由两者差值推导） */
const ragEvalRows = computed(() => {
  const data = ragEvaluation.value
  if (!data) return []
  const rows = [
    {
      key: 'accuracy',
      label: 'Accuracy',
      baseline: data.baseline.accuracy,
      rag: data.rag.accuracy,
      delta: data.delta.accuracy
    },
    {
      key: 'precision',
      label: 'Precision',
      baseline: data.baseline.precision,
      rag: data.rag.precision,
      delta: data.delta.precision
    },
    {
      key: 'recall',
      label: 'Recall',
      baseline: data.baseline.recall,
      rag: data.rag.recall,
      delta: data.delta.recall
    }
  ]
  return rows.map((row) => ({
    ...row,
    deltaText: formatDeltaPercent(
      row.delta ?? (row.baseline != null && row.rag != null ? row.rag - row.baseline : null)
    )
  }))
})

/* ---------- Embedding 供应商 / 模型（与复筛模型、语音转写同一套卡片网格） ---------- */

/** 可选 Embedding 供应商：注册表数据 + 当前生效 / 选中态（与复筛页同一合并函数） */
const embeddingCards = computed(() =>
  mergeScreeningProviders({
    providers: providers.value,
    view: { providerId: rag.value?.embedding?.providerId || '', providers: [] },
    selectedKey: embeddingForm.providerId
  })
)

const selectedEmbeddingCard = computed(
  () => embeddingCards.value.find((card) => card.selected) || null
)

/** 不可保存：卡片停用 / 未配 Key，或模型为空 */
const embeddingSaveBlocked = computed(
  () =>
    selectedEmbeddingCard.value?.disabled === true ||
    !embeddingForm.providerId ||
    !embeddingForm.model.trim()
)

/** 与当前生效 embedding 相比是否有改动 */
const embeddingDirty = computed(() => {
  const embedding = rag.value?.embedding
  if (!embedding) return false
  return (
    embeddingForm.providerId !== (embedding.providerId || '') ||
    embeddingForm.model.trim() !== (embedding.model || '')
  )
})

/** 模型候选：选中供应商的候选 + 后端 embedding.models + 当前手填值（允许手填新模型） */
const embeddingModelOptions = computed(() => {
  const models = []
  const push = (list) => {
    for (const model of list || []) {
      if (model && !models.includes(model)) models.push(model)
    }
  }
  push(selectedEmbeddingCard.value?.models)
  push(rag.value?.embedding?.models)
  if (embeddingForm.model) push([embeddingForm.model])
  return models
})

const embeddingHint = computed(() => {
  if (embeddingSaveBlocked.value) return t('settings.rag.embeddingBlockedHint')
  if (embeddingDirty.value) return t('settings.rag.embeddingDirtyHint')
  return ''
})

function embeddingReasonText(card) {
  if (card.disabledReason === 'disabled') return t('settings.model.providerDisabled')
  if (card.disabledReason === 'missingKey') return t('settings.model.providerMissingKey')
  return ''
}

function selectEmbeddingProvider(card) {
  if (card.disabled) return
  embeddingForm.providerId = card.key
  const current = rag.value?.embedding
  if (current && current.providerId === card.key) {
    embeddingForm.model = current.model
    return
  }
  embeddingForm.model = card.defaultModel || card.models[0] || ''
}

function openEmbeddingProviderEdit(card) {
  const row = providers.value.find(
    (item) => String(item.key || '').toLowerCase() === String(card.key || '').toLowerCase()
  )
  if (row) embeddingFormDialog.value?.open(row)
}

/** 供应商编辑保存后：刷新注册表与 RAG 视图（providerLabel / 状态行同步） */
async function refreshEmbeddingProviders() {
  await Promise.all([loadProviders(), loadRag()])
}

/** 加载供应商注册表（只在尚未加载时请求，避免与复筛页重复） */
function ensureProviders() {
  if (!canWrite.value) return
  if (!providers.value.length && !providersLoading.value) loadProviders()
}

/** 保存 Embedding 供应商 / 模型：PUT { embedding: { provider, model } } */
async function saveEmbedding() {
  if (ragEmbeddingSaving.value || !embeddingDirty.value || embeddingSaveBlocked.value) return
  ragEmbeddingSaving.value = true
  try {
    const data = await api.updateRagSettings({
      embedding: { provider: embeddingForm.providerId, model: embeddingForm.model.trim() }
    })
    rag.value = data
    syncRagForm(data)
    embeddingChanged.value = data.embeddingChanged === true
    ElMessage.success(t('settings.rag.embeddingSaved'))
  } catch {
    // 请求层已统一提示
  } finally {
    ragEmbeddingSaving.value = false
  }
}

/** 重建索引（变更后提示条内复用现有重建入口；成功后隐藏提示） */
async function reindexForEmbeddingChange() {
  const done = await runReindex('all')
  if (done) embeddingChanged.value = false
}

/** 同步后端返回的归一化设置到编辑控件（缺失字段回退契约默认值，便于继续保存） */
function syncRagForm(next) {
  const data = next || rag.value
  if (!data) return
  const retrieval = data.retrieval || {}
  ragForm.enabled = data.enabled === true
  ragForm.topK = retrieval.topK ?? RAG_DEFAULT_RETRIEVAL.topK
  ragForm.minScore = retrieval.minScore ?? RAG_DEFAULT_RETRIEVAL.minScore
  ragForm.maxContextChars = retrieval.maxContextChars ?? RAG_DEFAULT_RETRIEVAL.maxContextChars
  ragForm.corpusExamples = retrieval.corpusExamples ?? RAG_DEFAULT_RETRIEVAL.corpusExamples
  const embedding = data.embedding || {}
  embeddingForm.providerId = embedding.providerId || ''
  embeddingForm.model = embedding.model || ''
}

function ragPayload(enabled = ragForm.enabled) {
  return {
    enabled: !!enabled,
    topK: clampRagNumber(ragForm.topK, 1, 10, RAG_DEFAULT_RETRIEVAL.topK),
    minScore: clampRagNumber(ragForm.minScore, 0, 1, RAG_DEFAULT_RETRIEVAL.minScore),
    maxContextChars: clampRagNumber(
      ragForm.maxContextChars,
      500,
      8000,
      RAG_DEFAULT_RETRIEVAL.maxContextChars
    ),
    corpusExamples: clampRagNumber(
      ragForm.corpusExamples,
      0,
      10,
      RAG_DEFAULT_RETRIEVAL.corpusExamples
    )
  }
}

/** 控件清空 / 非法输入时回退默认值并夹取到契约范围，避免把 NaN 发给后端 */
function clampRagNumber(value, min, max, fallback) {
  const num = Number(value)
  if (!Number.isFinite(num)) return fallback
  return Math.min(max, Math.max(min, num))
}

async function loadRag() {
  if (!canWrite.value) return
  ragLoading.value = true
  ragError.value = ''
  try {
    rag.value = await api.getRagSettings()
    syncRagForm(rag.value)
  } catch (e) {
    // 请求层 silent：卡内展示 403/404 降级提示
    rag.value = null
    ragError.value = adminErrorMessage(e, t('settings.rag.loadFailed'))
  } finally {
    ragLoading.value = false
  }
}

async function saveRagSettings() {
  if (ragSaving.value) return
  ragSaving.value = true
  try {
    rag.value = await api.updateRagSettings(ragPayload())
    syncRagForm(rag.value)
    ElMessage.success(t('settings.rag.saved'))
  } catch {
    // 请求层已统一提示
  } finally {
    ragSaving.value = false
  }
}

/** 总开关：切换即 PUT 保存，失败时回滚开关位置 */
async function saveRagEnabled(value) {
  if (ragEnabledSaving.value) return
  ragEnabledSaving.value = true
  try {
    rag.value = await api.updateRagSettings(ragPayload(value))
    syncRagForm(rag.value)
    ElMessage.success(value ? t('settings.rag.enabledToast') : t('settings.rag.disabledToast'))
  } catch {
    ragForm.enabled = rag.value?.enabled === true
  } finally {
    ragEnabledSaving.value = false
  }
}

async function testRagEmbedding() {
  if (ragTesting.value) return
  ragTesting.value = true
  ragTestResult.value = null
  try {
    ragTestResult.value = await api.testRagEmbedding()
  } catch (e) {
    ragTestResult.value = {
      ok: false,
      providerLabel: '',
      model: '',
      dimensions: null,
      latencyMs: null,
      message: adminErrorMessage(e, t('settings.rag.testFailed'))
    }
  } finally {
    ragTesting.value = false
  }
}

function reindexLabel(scope) {
  if (scope === 'knowledge') return t('settings.rag.scopeKnowledge')
  if (scope === 'corpus') return t('settings.rag.scopeCorpus')
  return t('settings.rag.scopeAll')
}

async function runReindex(scope) {
  if (ragReindexing.value !== null) return false
  if (scope === 'all') {
    try {
      await ElMessageBox.confirm(
        t('settings.rag.reindexConfirmMessage'),
        t('settings.rag.reindexConfirmTitle'),
        {
          type: 'warning',
          confirmButtonText: t('settings.rag.reindexConfirmStart'),
          cancelButtonText: t('common.cancel')
        }
      )
    } catch {
      return false
    }
  }
  ragReindexing.value = scope
  try {
    const result = await api.reindexRag(scope)
    const counts = []
    if (result.queued !== null) counts.push(t('settings.rag.queued', { count: result.queued }))
    if (result.skipped !== null) {
      counts.push(t('settings.rag.skipped', { count: result.skipped }))
    }
    ElMessage.success(
      result.message ||
        t('settings.rag.reindexSubmitted', {
          scope: reindexLabel(scope),
          detail: counts.length
            ? t('settings.rag.reindexCountsDetail', {
                counts: counts.join(t('settings.rag.countSeparator'))
              })
            : ''
        })
    )
    return true
  } catch {
    // 请求层已统一提示
    return false
  } finally {
    ragReindexing.value = null
  }
}

async function runRagEvaluation() {
  if (ragEvaluating.value) return
  const sampleSize = Math.min(20, Math.max(5, Math.round(Number(ragSampleSize.value) || 10)))
  ragSampleSize.value = sampleSize
  ragEvaluating.value = true
  ragEvaluationError.value = ''
  try {
    ragEvaluation.value = await api.evaluateRag(sampleSize)
  } catch (e) {
    // 请求层 silent：保留上一次结果，仅在卡内提示失败原因
    ragEvaluationError.value = adminErrorMessage(e, t('settings.rag.evalFailedMsg'))
  } finally {
    ragEvaluating.value = false
  }
}

/** delta 以比例返回（如 0.1）：展示为 +10.0pp */
function formatDeltaPercent(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  const pp = num * 100
  const sign = pp > 0 ? '+' : ''
  return `${sign}${Number(pp.toFixed(1))}pp`
}

/** 已就绪 / 总数：任一缺失时显示占位，不臆造 0 */
function readyCountText(ready, total) {
  if (ready === null && total === null) return '—'
  return `${ready ?? '—'} / ${total ?? '—'}`
}

/** Embedding API Key 状态：只展示后端是否已配置，不接触明文 */
function embeddingKeyChip(configured) {
  if (configured === true) return { label: t('settings.state.configured'), type: 'ok' }
  if (configured === false) return { label: t('settings.state.notConfigured'), type: 'warn' }
  return { label: t('common.unknown'), type: 'info' }
}

/** 配置来源：与「当前 AI 复筛模型」一致的中文展示，未知值原样回退 */
function ragSourceLabel(source) {
  const text = String(source || '')
  if (!text) return ''
  const key = text.toLowerCase()
  if (key === 'defaults' || key === 'default') return t('settings.source.configDefault')
  if (key === 'database' || key === 'db') return t('settings.source.uiSaved')
  return text
}

onMounted(() => {
  loadRag()
  ensureProviders()
})
</script>

<style scoped>
/* RAG 检索增强 */
.provider-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.rag-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.rag-switch-main {
  min-width: 0;
}
.rag-switch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rag-note {
  margin-top: 5px;
  line-height: 1.65;
}
.rag-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin: 18px 0 10px;
}
.rag-kv-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.rag-kv {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fbfcfe;
}
.rag-kv-value {
  margin-top: 5px;
  font-size: 14px;
  line-height: 1.45;
  color: var(--ink-800);
}
.rag-alert {
  margin-top: 12px;
}
.rag-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.rag-eval-meta {
  margin-top: 10px;
  line-height: 1.7;
}
.rag-field {
  flex: 0 0 160px;
  min-width: 160px;
}
@media (max-width: 1100px) {
  .rag-kv-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .rag-kv-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 与 ④ / ⑦ 共用的指标与表格样式（scoped 到本组件，避免污染其他页面） */
.notify-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.notify-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.notify-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.notify-value {
  margin-top: 5px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.notify-sub {
  margin-top: 6px;
  line-height: 1.65;
}
.notify-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.notify-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
@media (max-width: 1100px) {
  .notify-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.ab-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.ab-value {
  margin-top: 5px;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.ab-sub {
  margin-top: 6px;
  line-height: 1.65;
}
.eval-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.eval-field {
  min-width: 200px;
  flex: 1;
}
.eval-field.eval-size {
  flex: 0 0 130px;
  min-width: 130px;
}
.eval-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.eval-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  padding-bottom: 1px;
}
.eval-running {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-700);
}
.eval-empty {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px dashed var(--border-strong, var(--border));
  border-radius: var(--r-md);
  line-height: 1.7;
  background: var(--ink-50);
}
.eval-error {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: #a32424;
}
.eval-error-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
.eval-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.eval-kpi {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fbfcfe;
}
@media (max-width: 1100px) {
  .eval-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .eval-field {
    min-width: 160px;
  }
}
.eval-reason {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.eval-reason.err {
  color: var(--danger);
}
.table-bleed {
  margin-left: calc(-1 * var(--panel-pad-x, 18px));
  margin-right: calc(-1 * var(--panel-pad-x, 18px));
}
.table-bleed :deep(.el-table .cell) {
  padding-left: var(--table-pad-x, 18px);
  padding-right: var(--table-pad-x, 18px);
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 测试结果 */
.test-result {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  font-size: 12.5px;
  line-height: 1.7;
}
.test-result.ok {
  background: var(--ok-soft);
  color: #14672f;
}
.test-result.bad {
  background: var(--danger-soft);
  color: #a32424;
}
.test-text {
  min-width: 0;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.test-msg {
  margin-top: 2px;
  word-break: break-all;
  opacity: 0.85;
}

.hint {
  margin-top: 12px;
  line-height: 1.7;
}
/* Embedding 配置：卡片网格标题 / 模型 / 只读运行信息 / 变更提示 */
.block-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 18px;
  margin-bottom: 10px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}
.embedding-form {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.embedding-field {
  width: 320px;
  max-width: 100%;
}
.embedding-spec {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 6px;
}
.embedding-spec b {
  color: var(--ink-700);
  font-weight: 600;
}
.embedding-changed {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid #f3d38a;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a06;
}
.embedding-changed.off {
  border-color: var(--border);
  background: var(--ink-50);
  color: var(--el-text-color-secondary);
}
.embedding-changed-text {
  flex: 1;
  min-width: 200px;
  line-height: 1.6;
}
@media (max-width: 640px) {
  .embedding-form {
    flex-direction: column;
    align-items: stretch;
  }
  .embedding-field {
    width: 100%;
  }
}
</style>
