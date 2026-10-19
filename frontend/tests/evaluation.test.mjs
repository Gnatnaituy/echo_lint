/**
 * A/B 配置 / 模型评测归一化单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AUDIT_ACTION_OPTIONS, auditActionLabel } from '../src/constants.js'
import {
  evaluationLabelMeta,
  formatAbPercent,
  formatLatencyMs,
  formatRatioPercent,
  normalizeAbConfig,
  normalizeEvaluation,
  normalizeRatio,
  normalizeScreenModel
} from '../src/utils/evaluation.js'

test('A/B 配置：契约字段归一化 + 百分数范围校验', () => {
  assert.deepEqual(
    normalizeAbConfig({ enabled: true, percent: 30, providerId: 'deepseek', model: 'deepseek-flash' }),
    {
      enabled: true,
      percent: 30,
      providerId: 'deepseek',
      model: 'deepseek-flash',
      providerLabel: ''
    }
  )
  // 字符串数字 / 别名
  const alias = normalizeAbConfig({ enabled: 'false', percentage: '0', provider: 'openai' })
  assert.equal(alias.enabled, false)
  assert.equal(alias.percent, 0)
  assert.equal(alias.providerId, 'openai')
  // 越界比例视为无效，不臆造
  assert.equal(normalizeAbConfig({ percent: 120 }).percent, null)
  assert.equal(normalizeAbConfig({ percent: -1 }).percent, null)
  assert.equal(normalizeAbConfig(null).enabled, null)
  assert.equal(normalizeAbConfig(null).percent, null)
})

test('screen-model 视图：保留原字段并只归一化 ab；缺失 ab 为 null', () => {
  const raw = {
    providerId: 'deepseek',
    providerLabel: 'DeepSeek',
    model: 'deepseek-flash',
    providers: [{ id: 'deepseek', models: ['a'] }],
    ab: { enabled: 'true', percent: '10', providerId: 'openai', model: 'gpt-4o-mini' }
  }
  const view = normalizeScreenModel(raw)
  assert.equal(view.providerId, 'deepseek')
  assert.equal(view.providers[0].id, 'deepseek')
  assert.equal(view.ab.enabled, true)
  assert.equal(view.ab.percent, 10)
  assert.equal(view.ab.providerId, 'openai')
  assert.equal(normalizeScreenModel({ providerId: 'x' }).ab, null)
  assert.equal(normalizeScreenModel(null), null)
})

test('比例归一化：0~1 原样、1~100 视为百分数、越界无效', () => {
  assert.equal(normalizeRatio(0.5), 0.5)
  assert.equal(normalizeRatio('0.25'), 0.25)
  assert.equal(normalizeRatio(50), 0.5)
  assert.equal(normalizeRatio(100), 1)
  assert.equal(normalizeRatio(120), null)
  assert.equal(normalizeRatio(-0.1), null)
  assert.equal(normalizeRatio(null), null)
})

test('评测结果：完整契约字段归一化（小数指标）', () => {
  const evaluation = normalizeEvaluation({
    total: 10,
    evaluated: 9,
    correct: 7,
    accuracy: 0.7777,
    precision: 0.8,
    recall: 0.75,
    tp: 6,
    fp: 1,
    tn: 1,
    fn: 2,
    latencyMs: 1234,
    byLabel: [
      { label: 'VIOLATION', total: 5, correct: 4 },
      { label: 'COMPLIANT', total: 4, correct: 3 }
    ],
    results: [
      { corpusId: 1, expected: 'VIOLATION', predicted: 'VIOLATION', confidence: 0.9, reason: '匹配' },
      { corpusId: 2, expected: 'COMPLIANT', predicted: 'VIOLATION', confidence: 0.6, reason: '误判' },
      { corpusId: 3, expected: 'COMPLIANT', error: 'timeout' }
    ]
  })
  assert.equal(evaluation.total, 10)
  assert.equal(evaluation.evaluated, 9)
  assert.equal(evaluation.correct, 7)
  assert.equal(evaluation.failed, 1) // 由 results 中 error 行推导
  assert.equal(evaluation.accuracy, 0.7777)
  assert.equal(evaluation.precision, 0.8)
  assert.equal(evaluation.recall, 0.75)
  assert.equal(evaluation.tp, 6)
  assert.equal(evaluation.fp, 1)
  assert.equal(evaluation.tn, 1)
  assert.equal(evaluation.fn, 2)
  assert.equal(evaluation.latencyMs, 1234)
  assert.equal(evaluation.byLabel.length, 2)
  assert.equal(evaluation.results.length, 3)
  assert.equal(evaluation.results[2].error, 'timeout')
  assert.equal(evaluation.results[1].confidence, 0.6)
})

test('评测结果：百分数指标归一化、缺失指标由混淆矩阵推导', () => {
  const evaluation = normalizeEvaluation({
    accuracy: 80,
    precision: 50,
    recall: 25,
    tp: 2,
    fp: 1,
    tn: 1,
    fn: 1
  })
  assert.equal(evaluation.accuracy, 0.8)
  assert.equal(evaluation.precision, 0.5)
  assert.equal(evaluation.recall, 0.25)
  // evaluated 由混淆矩阵求和推导
  assert.equal(evaluation.evaluated, 5)
  assert.equal(evaluation.correct, 3)
  // 未提供的结果 / 分类默认空数组
  assert.deepEqual(evaluation.byLabel, [])
  assert.deepEqual(evaluation.results, [])
  assert.equal(evaluation.failed, null)
  assert.equal(evaluation.total, null)

  const derived = normalizeEvaluation({ tp: 3, fp: 0, tn: 5, fn: 1, correct: 8, evaluated: 9 })
  assert.equal(derived.accuracy, 8 / 9)
  assert.equal(derived.precision, 1)
  assert.equal(derived.recall, 0.75)
})

test('评测结果：分母为 0 时指标为 null（显示占位，不显示 0%）', () => {
  const evaluation = normalizeEvaluation({ tp: 0, fp: 0, tn: 0, fn: 0 })
  assert.equal(evaluation.accuracy, null)
  assert.equal(evaluation.precision, null)
  assert.equal(evaluation.recall, null)
  assert.equal(evaluation.evaluated, null)
  assert.equal(evaluation.correct, null)
})

test('评测明细：兼容别名 / 大小写 / 对象 map 的 byLabel / 全零行过滤', () => {
  const evaluation = normalizeEvaluation({
    byLabel: {
      VIOLATION: { count: 4, correct: 3 },
      COMPLIANT: { total: 0, correct: 0 },
      OTHER: { total: 2, passed: 1 }
    },
    samples: [
      {
        corpus_id: '9',
        expectedLabel: 'violation',
        prediction: 'compliant',
        score: '0.75',
        detail: '理由'
      },
      { id: 10, groundTruth: 'COMPLIANT', actual: 'COMPLIANT', failed: true }
    ]
  })
  assert.deepEqual(
    evaluation.byLabel.map((r) => r.label),
    ['VIOLATION', 'OTHER']
  )
  assert.equal(evaluation.byLabel[0].total, 4)
  assert.equal(evaluation.byLabel[0].correct, 3)
  assert.equal(evaluation.results[0].corpusId, '9')
  assert.equal(evaluation.results[0].expected, 'VIOLATION')
  assert.equal(evaluation.results[0].predicted, 'COMPLIANT')
  assert.equal(evaluation.results[0].confidence, 0.75)
  assert.equal(evaluation.results[0].reason, '理由')
  assert.equal(evaluation.results[1].error, '调用失败')
})

test('标签 / 百分比 / 耗时展示格式化', () => {
  assert.equal(evaluationLabelMeta('VIOLATION').label, '违规')
  assert.equal(evaluationLabelMeta('compliant').label, '合规')
  assert.equal(evaluationLabelMeta('OTHER').label, 'OTHER')
  assert.equal(evaluationLabelMeta(null).label, '—')

  assert.equal(formatRatioPercent(0.8333), '83.3%')
  assert.equal(formatRatioPercent(0), '0%')
  assert.equal(formatRatioPercent(1), '100%')
  assert.equal(formatRatioPercent(null), '—')

  assert.equal(formatAbPercent(30), '30%')
  assert.equal(formatAbPercent(12.5), '12.5%')
  assert.equal(formatAbPercent('0'), '0%')
  assert.equal(formatAbPercent(120), '—')
  assert.equal(formatAbPercent(null), '—')

  assert.equal(formatLatencyMs(320), '320 ms')
  assert.equal(formatLatencyMs(1234), '1.23 s')
  assert.equal(formatLatencyMs(null), '—')
})

test('审计动作：MODEL_EVALUATE 有中文文案并进入筛选选项', () => {
  assert.equal(auditActionLabel('MODEL_EVALUATE'), '模型评测')
  assert.ok(AUDIT_ACTION_OPTIONS.some((opt) => opt.value === 'MODEL_EVALUATE'))
})
