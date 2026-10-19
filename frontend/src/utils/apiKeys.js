/**
 * 开放 API 密钥归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 4a）：
 * - GET  /api/api-keys        -> 列表（只回掩码：前缀 + 末 4 位，绝不回明文/哈希）
 * - POST /api/api-keys {name} -> 创建响应含一次性明文 {id,name,key,prefix,createdAt}
 * - DELETE /api/api-keys/{id} -> 停用（不物理删除）
 *
 * 列表接口可能返回裸数组或 Spring Page，两种都兼容；明文 key 只在创建响应里出现。
 */

import { asObject, pick, toBoolean, toNumber, toText } from './shape.js'

/** 单条密钥：兼容 prefix/keyPrefix、last4/suffix 等字段命名 */
export function normalizeApiKey(raw) {
  const data = asObject(raw)
  return {
    id: toNumber(pick(data, ['id', 'keyId'])),
    name: toText(pick(data, ['name'])),
    prefix: toText(pick(data, ['prefix', 'keyPrefix', 'key_prefix'])),
    last4: toText(pick(data, ['last4', 'lastFour', 'suffix', 'keySuffix'])),
    /** 仅创建响应携带明文；列表接口不应返回 */
    key: toText(pick(data, ['key', 'plaintext', 'apiKey'])),
    enabled: toBoolean(pick(data, ['enabled'])),
    createdBy: toText(pick(data, ['createdBy', 'created_by', 'creator'])),
    createdAt: toText(pick(data, ['createdAt', 'created_at'])),
    lastUsedAt: toText(pick(data, ['lastUsedAt', 'last_used_at', 'lastUsed']))
  }
}

/** 列表归一化：裸数组 / content / items / keys / data 包装 -> {items,total,page,size,totalPages} */
export function normalizeApiKeyList(raw) {
  if (Array.isArray(raw)) {
    const items = raw.map(normalizeApiKey)
    return { items, total: items.length, page: 0, size: items.length, totalPages: 0 }
  }
  const data = asObject(raw)
  let source = []
  for (const key of ['content', 'items', 'keys', 'data']) {
    if (Array.isArray(data[key])) {
      source = data[key]
      break
    }
  }
  const items = source.map(normalizeApiKey)
  return {
    items,
    total: toNumber(pick(data, ['totalElements', 'total', 'totalCount'])) ?? items.length,
    page: toNumber(pick(data, ['number', 'page', 'pageNumber'])) ?? 0,
    size: toNumber(pick(data, ['size', 'pageSize'])) ?? items.length,
    totalPages: toNumber(pick(data, ['totalPages', 'pages'])) ?? 0
  }
}

/** 密钥掩码展示：elk_ab12…cd34；只有前缀时显示「前缀…」 */
export function maskedApiKey(item) {
  const prefix = item?.prefix || ''
  const last4 = item?.last4 || ''
  if (prefix && last4) return `${prefix}…${last4}`
  if (prefix) return `${prefix}…`
  if (last4) return `…${last4}`
  return '—'
}

/** 创建响应是否带一次性明文（缺 key 时应提示用户密钥不可再查看） */
export function createdApiKeyText(raw) {
  return toText(raw?.key)
}
