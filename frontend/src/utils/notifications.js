/**
 * 运营通知（Webhook）设置 / 测试结果的防御性归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 2b-2）：
 * - GET  /api/settings/notifications      -> {enabled, urlConfigured, backlogThreshold, events:[string]}
 * - POST /api/settings/notifications/test -> {ok, latencyMs, message}
 *
 * 后端与前端并行开发、字段可能增补或暂缺，这里统一归一化为固定形状：
 * - 布尔字段：未返回 -> null（界面显示「未知」，不臆造 true/false）
 * - 数值字段：非数字 -> null
 * - 抽样比例：仅接受 0~1 的有限数字（超出范围视为无效，避免把 50 误显示成 5000%）
 */

function asObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

/** 依次取第一个非 null/undefined 的字段，兼容后端字段重命名 */
function pick(source, keys) {
  for (const key of keys) {
    if (source[key] !== undefined && source[key] !== null) return source[key]
  }
  return undefined
}

/** 宽松布尔：true/false、1/0、'true'/'false' 可识别，其余返回 null（未知） */
export function toBoolean(value) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value === 'boolean') return value
  if (value === 1 || value === '1' || value === 'true' || value === 'TRUE') return true
  if (value === 0 || value === '0' || value === 'false' || value === 'FALSE') return false
  return null
}

/** 宽松数字：非数字 / 空值返回 null */
export function toNumber(value) {
  if (value === undefined || value === null || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

/** 抽样比例：约定 0~1，越界视为后端未提供有效值 */
export function normalizeSamplingRate(value) {
  const rate = toNumber(value)
  if (rate === null || rate < 0 || rate > 1) return null
  return rate
}

/** 事件列表：兼容数组 / 逗号分隔字符串 / [{event|name|code}] 对象数组，去重并丢弃空值 */
export function normalizeEvents(value) {
  let raw = []
  if (Array.isArray(value)) raw = value
  else if (typeof value === 'string') raw = value.split(',')
  const events = []
  for (const item of raw) {
    let event = ''
    if (typeof item === 'string') event = item.trim()
    else if (item && typeof item === 'object') {
      event = String(item.event ?? item.name ?? item.code ?? '').trim()
    }
    if (event && !events.includes(event)) events.push(event)
  }
  return events
}

/** GET /api/settings/notifications 归一化 */
export function normalizeNotificationSettings(raw) {
  const data = asObject(raw)
  // 抽样比例当前不在契约内：兼容顶层字段与 nested sampling 对象，后端提供才展示
  const sampling = asObject(pick(data, ['sampling', 'sample']))
  const samplingRate =
    pick(data, ['samplingRate', 'dfaMissSampleRate', 'dfaMissRate', 'sampleRate']) ??
    pick(sampling, ['dfaMissRate', 'samplingRate', 'dfaMissSampleRate', 'rate'])
  return {
    enabled: toBoolean(pick(data, ['enabled', 'webhookEnabled'])),
    urlConfigured: toBoolean(
      pick(data, ['urlConfigured', 'webhookUrlConfigured', 'urlSet'])
    ),
    backlogThreshold: toNumber(
      pick(data, ['backlogThreshold', 'threshold', 'backlogThresholdCount'])
    ),
    samplingRate: normalizeSamplingRate(samplingRate),
    events: normalizeEvents(pick(data, ['events', 'eventTypes', 'supportedEvents']))
  }
}

/** POST /api/settings/notifications/test 归一化：接口异常时调用方自行兜底 */
export function normalizeNotificationTest(raw) {
  const data = asObject(raw)
  const message = pick(data, ['message', 'detail'])
  return {
    ok: toBoolean(pick(data, ['ok', 'success'])) === true,
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency'])),
    message: typeof message === 'string' ? message : ''
  }
}

/** 抽样比例展示：0 -> 0%，0.125 -> 12.5%，1 -> 100%；无效值不臆造，显示占位 */
export function formatSamplingRate(rate) {
  const normalized = normalizeSamplingRate(rate)
  if (normalized === null) return '—'
  return `${Number((normalized * 100).toFixed(1))}%`
}
