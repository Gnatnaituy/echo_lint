<template>
  <!-- ④ A/B 与模型评测（ADMIN）：A/B 配置只读展示 + 手动评测 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.ab.title') }}</div>
      <span class="tiny dim">{{ t('settings.ab.subtitle') }}</span>
    </div>
    <div class="panel-body">
      <!-- A/B 配置 -->
      <div v-if="abConfig" class="ab-grid">
        <div class="ab-item">
          <div class="ab-label">{{ t('settings.ab.split') }}</div>
          <div class="ab-value">
            <span class="chip" :class="abState.type">{{ abState.label }}</span>
          </div>
          <div class="ab-sub tiny dim">{{ abState.note }}</div>
        </div>
        <div class="ab-item">
          <div class="ab-label">{{ t('settings.ab.percent') }}</div>
          <div class="ab-value num">{{ formatAbPercent(abConfig.percent) }}</div>
          <div class="ab-sub tiny dim">
            {{ t('settings.ab.percentNote') }}
          </div>
        </div>
        <div class="ab-item">
          <div class="ab-label">{{ t('settings.ab.group') }}</div>
          <div class="ab-value ab-b">
            <span class="strong ellipsis">{{ abProviderLabel }}</span>
            <span class="chip info mono">{{
              abConfig.model || t('settings.ab.providerDefault')
            }}</span>
          </div>
          <div class="ab-sub tiny dim">{{ t('settings.ab.groupNote') }}</div>
        </div>
      </div>
      <div v-else class="eval-empty tiny dim">
        {{ view ? t('settings.ab.missing') : t('settings.ab.pending') }}
      </div>

      <!-- 评测表单 -->
      <div class="eval-form">
        <div class="eval-field">
          <label class="eval-label">{{ t('settings.eval.provider') }}</label>
          <el-select
            v-model="evalForm.providerId"
            :disabled="evaluating"
            :placeholder="t('settings.eval.providerPlaceholder')"
            @change="onEvalProviderChange"
          >
            <el-option
              v-for="opt in evalProviderOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
        <div class="eval-field">
          <label class="eval-label">{{ t('settings.eval.modelLabel') }}</label>
          <el-select
            v-model="evalForm.model"
            :disabled="evaluating"
            filterable
            allow-create
            clearable
            default-first-option
            :placeholder="t('settings.eval.modelPlaceholder')"
          >
            <el-option v-for="m in evalModelOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </div>
        <div class="eval-field eval-size">
          <label class="eval-label">{{ t('settings.eval.sampleSize') }}</label>
          <el-input-number
            v-model="evalForm.sampleSize"
            :min="1"
            :max="20"
            :precision="0"
            :disabled="evaluating"
            controls-position="right"
            style="width: 100%"
          />
        </div>
        <div class="eval-actions">
          <el-button
            type="primary"
            :loading="evaluating"
            :disabled="!evalForm.providerId || evaluating"
            @click="runEvaluation"
          >
            <el-icon v-if="!evaluating" class="el-icon--left"><DataAnalysis /></el-icon>
            {{ t('settings.eval.run') }}
          </el-button>
          <span v-if="!evalProviderOptions.length" class="tiny dim">
            {{ t('settings.eval.providerUnavailable') }}
          </span>
        </div>
      </div>

      <div v-if="evaluating" class="eval-running tiny dim">
        <el-icon class="is-loading"><Loading /></el-icon>
        {{ t('settings.eval.running') }}
      </div>

      <!-- 卡内错误 / 403/404 降级：已有结果时保留旧结果并提示 -->
      <div v-if="evaluationError" class="eval-error">
        <el-icon :size="16"><WarningFilled /></el-icon>
        <div class="eval-error-text">
          <div class="strong">{{ t('settings.eval.incomplete') }}</div>
          <div class="tiny dim">{{ evaluationError }}</div>
        </div>
        <el-button
          size="small"
          :disabled="evaluating || !evalForm.providerId"
          @click="runEvaluation"
        >
          {{ t('common.retry') }}
        </el-button>
      </div>

      <!-- 评测结果 -->
      <template v-if="evaluation">
        <div class="eval-kpis">
          <div class="eval-kpi">
            <div class="ab-label">Accuracy</div>
            <div class="ab-value num">{{ formatRatioPercent(evaluation.accuracy) }}</div>
          </div>
          <div class="eval-kpi">
            <div class="ab-label">Precision</div>
            <div class="ab-value num">{{ formatRatioPercent(evaluation.precision) }}</div>
          </div>
          <div class="eval-kpi">
            <div class="ab-label">Recall</div>
            <div class="ab-value num">{{ formatRatioPercent(evaluation.recall) }}</div>
          </div>
          <div class="eval-kpi">
            <div class="ab-label">{{ t('settings.eval.evaluatedRatio') }}</div>
            <div class="ab-value num">{{ evaluatedText }}</div>
            <div class="ab-sub tiny dim">
              {{
                t('settings.eval.correctFailed', {
                  correct: evaluation.correct ?? '—',
                  failed: evaluation.failed ?? '—'
                })
              }}
            </div>
          </div>
        </div>

        <div class="confusion-row">
          <div class="confusion-grid">
            <div class="conf-cell tp">
              <span class="conf-label">TP</span
              ><span class="conf-value num">{{ evaluation.tp }}</span>
            </div>
            <div class="conf-cell fp">
              <span class="conf-label">FP</span
              ><span class="conf-value num">{{ evaluation.fp }}</span>
            </div>
            <div class="conf-cell tn">
              <span class="conf-label">TN</span
              ><span class="conf-value num">{{ evaluation.tn }}</span>
            </div>
            <div class="conf-cell fn">
              <span class="conf-label">FN</span
              ><span class="conf-value num">{{ evaluation.fn }}</span>
            </div>
          </div>
          <div class="conf-legend tiny dim">
            <span>{{ t('settings.eval.total', { total: evaluation.total ?? '—' }) }}</span>
            <span class="sep">·</span>
            <span>{{
              t('settings.eval.latency', { value: formatLatencyMs(evaluation.latencyMs) })
            }}</span>
            <span class="sep">·</span>
            <span>{{ t('settings.eval.failedExcluded') }}</span>
          </div>
        </div>

        <div class="eval-section-head">
          <div class="block-title">{{ t('settings.eval.byLabelTitle') }}</div>
          <span class="tiny dim">{{ t('settings.eval.byLabelNote') }}</span>
        </div>
        <div class="table-bleed">
          <el-table :data="evaluation.byLabel" size="small">
            <el-table-column :label="t('settings.eval.labelColumn')" min-width="140">
              <template #default="{ row }">
                <span class="chip" :class="evaluationLabelMeta(row.label).type">
                  {{ evaluationLabelMeta(row.label).label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.sampleSize')" width="110" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.total }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.correct')" width="110" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.correct }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.accuracy')" width="130" align="right">
              <template #default="{ row }">
                <span class="num">{{ formatRatioPercent(labelAccuracy(row)) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-block">
                <div class="empty-title">{{ t('settings.eval.byLabelEmpty') }}</div>
                <div class="tiny dim">{{ t('settings.eval.byLabelEmptyHint') }}</div>
              </div>
            </template>
          </el-table>
        </div>

        <div class="eval-section-head">
          <div class="block-title">{{ t('settings.eval.resultsTitle') }}</div>
          <span class="tiny dim">{{
            t('settings.eval.resultsNote', { count: evaluation.results.length })
          }}</span>
        </div>
        <div class="table-bleed">
          <el-table :data="evaluation.results" size="small" max-height="420">
            <el-table-column :label="t('settings.eval.corpusId')" width="110">
              <template #default="{ row }">
                <span class="mono">{{ row.corpusId || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.expected')" width="100" align="center">
              <template #default="{ row }">
                <span class="chip" :class="evaluationLabelMeta(row.expected).type">
                  {{ evaluationLabelMeta(row.expected).label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.predicted')" width="100" align="center">
              <template #default="{ row }">
                <span v-if="row.error" class="chip danger">{{ t('common.failed') }}</span>
                <span v-else class="chip" :class="evaluationLabelMeta(row.predicted).type">
                  {{ evaluationLabelMeta(row.predicted).label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.confidence')" width="100" align="right">
              <template #default="{ row }">
                <span class="num">{{
                  row.confidence == null ? '—' : formatRatioPercent(row.confidence)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('settings.eval.reason')" min-width="280">
              <template #default="{ row }">
                <el-tooltip
                  v-if="row.error || row.reason"
                  :content="row.error || row.reason"
                  placement="top"
                  :show-after="300"
                  :popper-style="{ maxWidth: '520px', wordBreak: 'break-all' }"
                >
                  <span class="eval-reason" :class="{ err: !!row.error }">
                    {{ row.error || row.reason }}
                  </span>
                </el-tooltip>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="88" align="center">
              <template #default="{ row }">
                <span class="chip" :class="row.error ? 'danger' : 'ok'">
                  {{ row.error ? t('common.failed') : t('common.success') }}
                </span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-block">
                <div class="empty-title">{{ t('settings.eval.resultsEmpty') }}</div>
                <div class="tiny dim">{{ t('settings.eval.resultsEmptyHint') }}</div>
              </div>
            </template>
          </el-table>
        </div>
      </template>

      <!-- 空态 -->
      <div v-else-if="!evaluating && !evaluationError" class="eval-empty tiny dim">
        {{ t('settings.eval.empty') }}
        {{ t('settings.eval.emptyHint') }}
      </div>

      <div class="hint tiny dim">
        {{ t('settings.eval.hintA') }}
        {{ t('settings.eval.hintB') }}
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import { isProviderEnabled } from '../../utils/providers'
import {
  evaluationLabelMeta,
  formatAbPercent,
  formatLatencyMs,
  formatRatioPercent
} from '../../utils/evaluation'
import { useSettingsContext } from './context'
import { DataAnalysis, Loading, WarningFilled } from '../../components/icons.js'

const { t } = useI18n()
const { model, registry } = useSettingsContext()
const { view } = model
const { providers } = registry

// A/B 与模型评测（仅 ADMIN）：ab 随 screen-model 视图下发（只读）；评测调用真实模型
const evalForm = reactive({ providerId: '', model: '', sampleSize: 10 })
const evaluating = ref(false)
const evaluation = ref(null)
const evaluationError = ref('')

const abConfig = computed(() => view.value?.ab || null)

/** A/B 状态：接口未返回 ab 时显示占位，不臆造启用状态 */
const abState = computed(() => {
  if (!view.value) return { label: t('common.loading'), type: 'info', note: '' }
  const ab = abConfig.value
  if (!ab) {
    return {
      label: t('settings.ab.stateNotConfigured'),
      type: 'info',
      note: t('settings.ab.noteNoConfig')
    }
  }
  if (ab.enabled === false) {
    return {
      label: t('settings.state.disabled'),
      type: 'info',
      note: t('settings.ab.noteDisabled')
    }
  }
  if (ab.enabled === true) {
    return {
      label: t('settings.state.enabled'),
      type: 'ok',
      note:
        ab.percent == null
          ? t('settings.ab.notePercentMissing')
          : t('settings.ab.notePercent', { percent: formatAbPercent(ab.percent) })
    }
  }
  return {
    label: t('settings.ab.stateUnknown'),
    type: 'warn',
    note: t('settings.ab.noteUnknown')
  }
})

/** B 组供应商：优先用注册表里的展示名，缺失时回退原始 key */
const abProviderLabel = computed(() => {
  const id = abConfig.value?.providerId
  if (!id) return '—'
  const found = providers.value.find((p) => String(p.key || '').toLowerCase() === id.toLowerCase())
  return found?.label || id
})

/** 评测供应商候选：启用中的注册表供应商；注册表不可用时回退当前生效供应商 */
const evalProviderOptions = computed(() => {
  const list = providers.value
    .filter((p) => p.key && isProviderEnabled(p))
    .map((p) => ({ value: p.key, label: p.label || p.key }))
  if (list.length) return list
  const current = view.value
  if (current?.providerId) {
    return [{ value: current.providerId, label: current.providerLabel || current.providerId }]
  }
  return []
})

/** 当前选中供应商的候选模型：注册表优先，其次 screen-model 视图 */
const evalModelOptions = computed(() => {
  const selected = String(evalForm.providerId || '').toLowerCase()
  if (!selected) return []
  const registered = providers.value.find((p) => String(p.key || '').toLowerCase() === selected)
  if (registered?.models?.length) return registered.models
  const current = view.value?.providers?.find((p) => String(p.id || '').toLowerCase() === selected)
  return current?.models || []
})

const evaluatedText = computed(() => {
  const data = evaluation.value
  if (!data) return '—'
  const evaluated = data.evaluated == null ? '—' : data.evaluated
  return data.total == null ? `${evaluated}` : `${evaluated} / ${data.total}`
})

function onEvalProviderChange() {
  if (!evalModelOptions.value.includes(evalForm.model)) evalForm.model = ''
}

/** byLabel 行准确率：无样本时返回 null（显示占位） */
function labelAccuracy(row) {
  const total = Number(row?.total) || 0
  if (total <= 0) return null
  return (Number(row?.correct) || 0) / total
}

/** 评测接口错误：403/404 给出可读降级，其余保留后端 message（如配额不足） */
function evalErrorMessage(error) {
  const status = error?.status
  if (status === 403) return t('settings.eval.errorForbidden')
  if (status === 404) return t('settings.eval.errorNotFound')
  return error?.message || t('settings.eval.errorGeneric')
}

async function runEvaluation() {
  if (evaluating.value) return
  const providerId = evalForm.providerId
  if (!providerId) {
    ElMessage.warning(t('settings.eval.selectProviderFirst'))
    return
  }
  const sampleSize = Math.min(20, Math.max(1, Math.round(Number(evalForm.sampleSize) || 10)))
  evalForm.sampleSize = sampleSize
  const payload = { providerId, sampleSize }
  const model = evalForm.model.trim()
  if (model) payload.model = model

  evaluating.value = true
  evaluationError.value = ''
  try {
    evaluation.value = await api.evaluateScreenModel(payload)
  } catch (e) {
    // 请求层 silent：保留上一次结果，仅在卡内提示失败原因
    evaluationError.value = evalErrorMessage(e)
  } finally {
    evaluating.value = false
  }
}

// 供应商数据晚于页面加载：自动选中当前生效供应商并清理失效模型。
// immediate：本卡片是异步组件，可能晚于供应商列表返回才挂载（拆分前 watch 总在挂载前注册，
// 故挂载时若候选已就绪需补跑一次，保持与拆分前一致的自动选中行为）。
watch(
  evalProviderOptions,
  () => {
    const options = evalProviderOptions.value
    if (!options.length) return
    if (!options.some((opt) => opt.value === evalForm.providerId)) {
      const current = options.find((opt) => opt.value === view.value?.providerId)
      evalForm.providerId = (current || options[0]).value
    }
    if (evalForm.model && !evalModelOptions.value.includes(evalForm.model)) {
      evalForm.model = ''
    }
  },
  { immediate: true }
)
</script>

<style scoped>
/* A/B 与模型评测 */
.ab-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.ab-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
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
.ab-value.ab-b {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 14px;
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
  /* 主操作统一贴右：与 A/B 评测行、检索参数保存按钮右缘对齐 */
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
.confusion-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.confusion-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(74px, 1fr));
  gap: 8px;
  flex: 1;
  min-width: 320px;
}
.conf-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  background: var(--ink-50);
  border: 1px solid var(--border);
}
.conf-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.conf-value {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-800);
}
.conf-cell.tp,
.conf-cell.tn {
  background: var(--ok-soft);
  border-color: transparent;
}
.conf-cell.fp,
.conf-cell.fn {
  background: var(--danger-soft);
  border-color: transparent;
}
.conf-legend {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.eval-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin: 18px 0 10px;
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
@media (max-width: 1100px) {
  .ab-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .eval-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .eval-field {
    min-width: 160px;
  }
}

/* 嵌套在 .panel-body 中的表格：左右各吃掉 body 的 18px 内边距，
   让表格贴到卡片边缘、首列文字与 panel-head 标题同处 18px 基线。
   单元格自身内边距仍由全局 .el-table .cell（18px）提供，不叠加。 */
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
.hint {
  margin-top: 12px;
  line-height: 1.7;
}
</style>
