/**
 * AI 用量与成本统计归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 3a）：
 * GET /api/stats/ai-usage?from&to -> {
 *   requests, inputTokens, outputTokens, costUsd,
 *   byDay[], byProvider[], byModel[]
 * }
 *
 * 明细行字段名后端可能略有出入（date/day、providerKey/provider、model/modelName 等），
 * 这里统一兼容并做展示前的清洗：全零行不展示、按日升序、按成本倒序。
 */

import { asObject, pick, toNumber, toText } from './shape.js'

function nonNegative(value) {
  const num = toNumber(value)
  return num !== null && num > 0 ? num : 0
}

function asRows(value) {
  if (Array.isArray(value)) return value
  const data = asObject(value)
  return Object.entries(data).map(([key, row]) => ({ key, ...asObject(row) }))
}

function usageFields(row) {
  return {
    requests: nonNegative(pick(row, ['requests', 'requestCount', 'count'])),
    inputTokens: nonNegative(pick(row, ['inputTokens', 'promptTokens', 'inputTokensCount'])),
    outputTokens: nonNegative(
      pick(row, ['outputTokens', 'completionTokens', 'outputTokensCount'])
    ),
    costUsd: nonNegative(pick(row, ['costUsd', 'cost', 'costUSD']))
  }
}

function hasUsage(row) {
  return row.requests > 0 || row.inputTokens > 0 || row.outputTokens > 0 || row.costUsd > 0
}

function byCostDesc(a, b) {
  return b.costUsd - a.costUsd || b.requests - a.requests
}

function normalizeByDay(value) {
  return asRows(value)
    .map((row) => ({
      date: toText(pick(row, ['date', 'day', 'bucket', 'key'])),
      ...usageFields(row)
    }))
    .filter((row) => row.date && hasUsage(row))
    .sort((a, b) => String(a.date).localeCompare(String(b.date)))
}

function normalizeByProvider(value) {
  return asRows(value)
    .map((row) => {
      const key = toText(pick(row, ['providerKey', 'key', 'provider', 'providerId']))
      const label = toText(pick(row, ['providerLabel', 'label', 'name'])) || key
      return { key, label, ...usageFields(row) }
    })
    .filter((row) => (row.key || row.label) && hasUsage(row))
    .sort(byCostDesc)
}

function normalizeByModel(value) {
  return asRows(value)
    .map((row) => ({
      model: toText(pick(row, ['model', 'modelName', 'name', 'key'])),
      providerLabel: toText(pick(row, ['providerLabel', 'provider', 'label'])),
      ...usageFields(row)
    }))
    .filter((row) => row.model && hasUsage(row))
    .sort(
      (a, b) =>
        byCostDesc(a, b) || String(a.model).localeCompare(String(b.model))
    )
}

/** GET /api/stats/ai-usage 归一化：总计缺字段按 0，明细为空数组 */
export function normalizeAiUsage(raw) {
  const data = asObject(raw)
  return {
    requests: nonNegative(pick(data, ['requests', 'requestCount', 'totalRequests'])),
    inputTokens: nonNegative(
      pick(data, ['inputTokens', 'promptTokens', 'totalInputTokens'])
    ),
    outputTokens: nonNegative(
      pick(data, ['outputTokens', 'completionTokens', 'totalOutputTokens'])
    ),
    costUsd: nonNegative(pick(data, ['costUsd', 'cost', 'totalCostUsd'])),
    byDay: normalizeByDay(pick(data, ['byDay', 'daily', 'days', 'byDate'])),
    byProvider: normalizeByProvider(pick(data, ['byProvider', 'providers'])),
    byModel: normalizeByModel(pick(data, ['byModel', 'models']))
  }
}
