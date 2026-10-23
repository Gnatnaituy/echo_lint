/**
 * 「AI 对话记录」归一化与展示辅助。
 *
 * 后端契约（Batch 6）：
 * - GET /api/ai-invocations?stage=&providerId=&model=&success=&recordingId=&from=&to=&page=&size=
 *   -> Spring Page<摘要>（不含 requestJson/responseJson）
 * - GET /api/ai-invocations/{id} -> 详情（含完整 requestJson/responseJson）
 * - GET /api/ai-invocations/facets -> {stages, providers:[{id,label}], models}
 *
 * 列表/详情字段可能增补或暂缺，缺失统一返回 null / '' / []，不臆造业务值。
 */

import { t } from '../i18n/index.js'
import { asObject, pick, toBoolean, toNumber, toText } from './shape.js'

// label 用 getter：模板 / 计算属性读取时自动跟随语言切换；默认语言为中文
export const AI_STAGE_META = {
  SCREEN: { get label() { return t('meta.aiStage.SCREEN') }, type: 'brand' },
  MINING: { get label() { return t('meta.aiStage.MINING') }, type: 'info' },
  EMBED_QUERY: { get label() { return t('meta.aiStage.EMBED_QUERY') }, type: 'info' },
  EMBED_INDEX: { get label() { return t('meta.aiStage.EMBED_INDEX') }, type: 'info' }
}

export function aiStageMeta(stage) {
  const value = toText(stage)
  if (!value) return { label: '—', type: 'info' }
  return AI_STAGE_META[value] || { label: value, type: 'info' }
}

/** 状态：失败 > 降级 > 成功 */
export function aiStatusMeta(row) {
  const data = asObject(row)
  if (toBoolean(pick(data, ['success'])) === false) {
    return { get label() { return t('meta.aiStatus.FAILED') }, type: 'danger' }
  }
  if (toBoolean(pick(data, ['degraded'])) === true) {
    return { get label() { return t('meta.aiStatus.DEGRADED') }, type: 'warn' }
  }
  return { get label() { return t('meta.aiStatus.SUCCESS') }, type: 'ok' }
}

function normalizeSummary(raw) {
  const data = asObject(raw)
  return {
    id: toNumber(pick(data, ['id', 'invocationId'])),
    recordingId: toNumber(pick(data, ['recordingId', 'recording_id'])),
    stage: toText(pick(data, ['stage'])),
    providerId: toText(pick(data, ['providerId', 'provider_id', 'provider'])),
    providerLabel: toText(pick(data, ['providerLabel', 'provider_label'])),
    model: toText(pick(data, ['model'])),
    endpoint: toText(pick(data, ['endpoint'])),
    success: toBoolean(pick(data, ['success'])),
    degraded: toBoolean(pick(data, ['degraded'])),
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency_ms'])),
    requestChars: toNumber(pick(data, ['requestChars', 'request_chars'])),
    responseChars: toNumber(pick(data, ['responseChars', 'response_chars'])),
    inputTokens: toNumber(pick(data, ['inputTokens', 'input_tokens'])),
    outputTokens: toNumber(pick(data, ['outputTokens', 'output_tokens'])),
    costMicros: toNumber(pick(data, ['costMicros', 'cost_micros'])),
    errorMessage: toText(pick(data, ['errorMessage', 'error_message', 'error'])),
    createdAt: toText(pick(data, ['createdAt', 'created_at']))
  }
}

export function normalizeAiInvocationSummary(raw) {
  return normalizeSummary(raw)
}

/** 列表归一化：Spring Page 或裸数组 */
export function normalizeAiInvocationPage(raw) {
  if (Array.isArray(raw)) {
    const items = raw.map(normalizeSummary)
    return { items, total: items.length, page: 0, size: items.length, totalPages: 0 }
  }
  const data = asObject(raw)
  let source = []
  for (const key of ['content', 'items', 'data']) {
    if (Array.isArray(data[key])) {
      source = data[key]
      break
    }
  }
  const items = source.map(normalizeSummary)
  return {
    items,
    total: toNumber(pick(data, ['totalElements', 'total', 'totalCount'])) ?? items.length,
    page: toNumber(pick(data, ['number', 'page', 'pageNumber'])) ?? 0,
    size: toNumber(pick(data, ['size', 'pageSize'])) ?? items.length,
    totalPages: toNumber(pick(data, ['totalPages', 'pages'])) ?? 0
  }
}

/** 详情：摘要字段 + 完整请求/回复 */
export function normalizeAiInvocationDetail(raw) {
  const data = asObject(raw)
  return {
    ...normalizeSummary(data),
    requestJson: toText(pick(data, ['requestJson', 'request_json'])),
    responseJson: toText(pick(data, ['responseJson', 'response_json']))
  }
}

/** 筛选下拉候选 */
export function normalizeAiInvocationFacets(raw) {
  const data = asObject(raw)
  const providers = Array.isArray(data.providers)
    ? data.providers
        .map((item) => {
          const provider = asObject(item)
          return {
            id: toText(pick(provider, ['id', 'providerId', 'key'])),
            label: toText(pick(provider, ['label', 'providerLabel', 'name']))
          }
        })
        .filter((item) => item.id)
    : []
  return {
    stages: Array.isArray(data.stages) ? data.stages.map(toText).filter(Boolean) : [],
    providers,
    models: Array.isArray(data.models) ? data.models.map(toText).filter(Boolean) : []
  }
}

/** 从请求体 JSON 解析 messages（embedding 请求没有 messages，返回空数组） */
export function parseAiMessages(requestJson) {
  if (!requestJson) return []
  try {
    const parsed = typeof requestJson === 'string' ? JSON.parse(requestJson) : requestJson
    const messages = parsed && Array.isArray(parsed.messages) ? parsed.messages : []
    return messages
      .map((message) => {
        const item = asObject(message)
        return {
          role: toText(pick(item, ['role'])),
          content: toText(pick(item, ['content']))
        }
      })
      .filter((message) => message.content)
  } catch {
    return []
  }
}

/** JSON 字符串格式化；解析失败原样返回 */
export function prettyJson(raw) {
  if (raw === null || raw === undefined) return ''
  if (typeof raw !== 'string') return JSON.stringify(raw, null, 2)
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 角色 code -> meta.aiRole.* key；未命中的角色保持原样返回 */
const AI_ROLE_KEYS = new Map([
  ['system', 'meta.aiRole.SYSTEM'],
  ['user', 'meta.aiRole.USER'],
  ['assistant', 'meta.aiRole.ASSISTANT'],
  ['tool', 'meta.aiRole.TOOL']
])

export function roleLabel(role) {
  const value = toText(role)
  const key = AI_ROLE_KEYS.get(value)
  if (key) return t(key)
  return value || t('meta.aiRole.MESSAGE')
}

export function formatCostUsd(costMicros) {
  const micros = toNumber(costMicros)
  if (micros === null) return '—'
  return `$${(micros / 1_000_000).toFixed(6)}`
}

export function formatTokens(row) {
  const data = asObject(row)
  const input = toNumber(pick(data, ['inputTokens', 'input_tokens']))
  const output = toNumber(pick(data, ['outputTokens', 'output_tokens']))
  if (input === null && output === null) return '—'
  return `${input ?? '—'} / ${output ?? '—'}`
}

export function formatLatency(ms) {
  const value = toNumber(ms)
  return value === null ? '—' : `${value} ms`
}

export function formatChars(value) {
  const chars = toNumber(value)
  if (chars === null) return '—'
  if (chars >= 1000) return `${(chars / 1000).toFixed(1)}k`
  return String(chars)
}
