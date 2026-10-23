/**
 * 模型 A/B 配置与评测结果归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 3b-2）：
 * - GET  /api/settings/screen-model 新增 ab: {enabled, percent(0~100), providerId, model}
 * - POST /api/settings/screen-model/evaluate {providerId, model?, sampleSize?} ->
 *   {total, evaluated, correct, accuracy, precision, recall, tp, fp, tn, fn, latencyMs,
 *    failed?, byLabel:[{label,total,correct}], results:[{corpusId,expected,predicted,confidence,reason,error?}]}
 *
 * 指标可能以 0~1 小数或 0~100 百分数返回，统一归一化为 0~1 小数；
 * 缺失指标可由混淆矩阵推导（仅在底层数据存在时），否则返回 null 由界面显示占位。
 */

import { asObject, pick, toBoolean, toNumber, toText } from './shape.js'
import { t } from '../i18n/index.js'

/* ---------- 标注标签 ---------- */

/** label 用 getter：读取时按当前语言翻译，视图重渲染即生效 */
const EVAL_LABEL_META = {
  VIOLATION: {
    get label() {
      return t('meta.evaluationLabel.VIOLATION')
    },
    type: 'danger'
  },
  COMPLIANT: {
    get label() {
      return t('meta.evaluationLabel.COMPLIANT')
    },
    type: 'ok'
  }
}

/** 语料标注 / 模型预测标签：已知枚举中文展示，未知值原样回退 */
export function evaluationLabelMeta(label) {
  const key = toText(label).toUpperCase()
  return EVAL_LABEL_META[key] || { label: key || '—', type: 'info' }
}

/* ---------- A/B 配置 ---------- */

/** A/B 配置：percent 约定 0~100，越界视为无效（不臆造） */
export function normalizeAbConfig(raw) {
  const data = asObject(raw)
  const percent = toNumber(pick(data, ['percent', 'percentage', 'ratio']))
  return {
    enabled: toBoolean(pick(data, ['enabled'])),
    percent: percent !== null && percent >= 0 && percent <= 100 ? percent : null,
    providerId: toText(pick(data, ['providerId', 'provider', 'providerKey'])),
    model: toText(pick(data, ['model'])),
    providerLabel: toText(pick(data, ['providerLabel', 'label']))
  }
}

/**
 * screen-model 视图：保留全部原字段（providers / providerId / model 等），
 * 仅把新增的 ab 字段归一化；后端未返回时 ab = null，便于界面区分「未配置」。
 */
export function normalizeScreenModel(raw) {
  if (raw === undefined || raw === null) return null
  if (typeof raw !== 'object' || Array.isArray(raw)) return raw
  const ab = pick(raw, ['ab', 'abConfig'])
  return { ...raw, ab: ab === undefined ? null : normalizeAbConfig(ab) }
}

/* ---------- 比例 / 指标 ---------- */

/** 比例归一化：0~1 原样；>1 且 <=100 视为百分数除以 100；其余视为无效 */
export function normalizeRatio(value) {
  const num = toNumber(value)
  if (num === null || num < 0) return null
  if (num <= 1) return num
  if (num <= 100) return num / 100
  return null
}

function nonNegative(value) {
  const num = toNumber(value)
  return num !== null && num > 0 ? num : 0
}

function normalizeByLabel(raw) {
  let rows = []
  if (Array.isArray(raw)) rows = raw
  else if (raw && typeof raw === 'object') {
    rows = Object.entries(raw).map(([label, value]) => ({ label, ...asObject(value) }))
  }
  return rows
    .map((row) => {
      const data = asObject(row)
      return {
        label: toText(pick(data, ['label', 'type', 'category', 'expected'])) || t('common.unlabeled'),
        total: nonNegative(pick(data, ['total', 'count', 'samples'])),
        correct: nonNegative(pick(data, ['correct', 'passed', 'hits']))
      }
    })
    .filter((row) => row.total > 0)
}

function normalizeResults(raw) {
  const rows = Array.isArray(raw) ? raw : []
  return rows
    .filter((row) => row && typeof row === 'object')
    .map((row) => {
      const data = asObject(row)
      const errorRaw = pick(data, ['error', 'errorMessage', 'failure', 'failed'])
      const error = typeof errorRaw === 'boolean'
        ? (errorRaw ? t('settings.eval.callFailed') : '')
        : toText(errorRaw)
      return {
        corpusId: toText(pick(data, ['corpusId', 'corpus_id', 'id'])),
        expected: toText(pick(data, ['expected', 'expectedLabel', 'truth', 'groundTruth'])).toUpperCase(),
        predicted: toText(pick(data, ['predicted', 'prediction', 'actual'])).toUpperCase(),
        confidence: normalizeRatio(pick(data, ['confidence', 'score'])),
        reason: toText(pick(data, ['reason', 'detail', 'message'])),
        error
      }
    })
}

/* ---------- 评测结果 ---------- */

/** POST /api/settings/screen-model/evaluate 响应归一化 */
export function normalizeEvaluation(raw) {
  const data = asObject(raw)
  const tp = nonNegative(pick(data, ['tp', 'truePositive', 'truePositives']))
  const fp = nonNegative(pick(data, ['fp', 'falsePositive', 'falsePositives']))
  const tn = nonNegative(pick(data, ['tn', 'trueNegative', 'trueNegatives']))
  const fn = nonNegative(pick(data, ['fn', 'falseNegative', 'falseNegatives']))

  const results = normalizeResults(pick(data, ['results', 'samples', 'items']))
  const byLabel = normalizeByLabel(pick(data, ['byLabel', 'labels']))

  const confusionSum = tp + fp + tn + fn
  const evaluated = toNumber(pick(data, ['evaluated', 'evaluatedCount']))
    ?? (confusionSum > 0 ? confusionSum : null)
  const total = toNumber(pick(data, ['total', 'totalCount']))
  const correctRaw = toNumber(pick(data, ['correct', 'correctCount']))
  const correct = correctRaw ?? (tp + tn > 0 ? tp + tn : null)

  const failedRaw = toNumber(pick(data, ['failed', 'failedCount', 'errorCount']))
  const failed = failedRaw ?? (results.length ? results.filter((r) => r.error).length : null)

  const accuracy = normalizeRatio(pick(data, ['accuracy']))
    ?? (evaluated && correct !== null ? correct / evaluated : null)
  const precision = normalizeRatio(pick(data, ['precision']))
    ?? (tp + fp > 0 ? tp / (tp + fp) : null)
  const recall = normalizeRatio(pick(data, ['recall']))
    ?? (tp + fn > 0 ? tp / (tp + fn) : null)

  return {
    total,
    evaluated,
    correct,
    failed,
    accuracy,
    precision,
    recall,
    tp,
    fp,
    tn,
    fn,
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency_ms', 'latency'])),
    byLabel,
    results
  }
}

/* ---------- 展示格式化 ---------- */

/** 0~1 小数 -> 百分比字符串；无效值显示占位（不臆造 0%） */
export function formatRatioPercent(value) {
  const ratio = normalizeRatio(value)
  if (ratio === null) return '—'
  return `${Number((ratio * 100).toFixed(1))}%`
}

/** A/B 占比（0~100）-> 百分比字符串 */
export function formatAbPercent(value) {
  const num = toNumber(value)
  if (num === null || num < 0 || num > 100) return '—'
  return `${Number(num.toFixed(1))}%`
}

/** 评测耗时：<1s 显示 ms，否则秒（保留 2 位） */
export function formatLatencyMs(value) {
  const num = toNumber(value)
  if (num === null || num < 0) return '—'
  if (num < 1000) return `${Math.round(num)} ms`
  return `${(num / 1000).toFixed(2)} s`
}
