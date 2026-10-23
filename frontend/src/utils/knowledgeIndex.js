/**
 * 「索引明细」分块接口归一化。
 *
 * 后端契约（与后端同事并行实现，字段名以此为准）：
 * - GET /api/knowledge/{id}/chunks?page=&size= （ADMIN） ->
 *   {entryId, title, indexStatus, indexError, indexedAt, embeddingHash,
 *    embeddingModel, embeddingDimensions,
 *    total, page, size,
 *    items: [{chunkId, chunkIndex, content, contentHash,
 *             embeddingModel, embeddingDim, createdAt, updatedAt}]}
 *
 * 字段可能暂缺 / 增补：缺失统一返回 null / '' / []，界面据此显示「—」，
 * 不臆造索引状态。兼容 snake_case 别名与 Spring Page 形态
 * （content/totalElements/number/size）。
 */

import { asObject, pick, toNumber, toText } from './shape.js'

/** 单条分块 */
export function normalizeKnowledgeChunk(raw) {
  const data = asObject(raw)
  return {
    chunkId: toNumber(pick(data, ['chunkId', 'chunk_id', 'id'])),
    chunkIndex: toNumber(pick(data, ['chunkIndex', 'chunk_index', 'index'])),
    content: toText(pick(data, ['content', 'text', 'body'])),
    contentHash: toText(pick(data, ['contentHash', 'content_hash', 'hash'])),
    embeddingModel: toText(pick(data, ['embeddingModel', 'embedding_model', 'model'])),
    embeddingDim: toNumber(pick(data, ['embeddingDim', 'embedding_dim', 'dim', 'dimensions'])),
    createdAt: toText(pick(data, ['createdAt', 'created_at'])),
    updatedAt: toText(pick(data, ['updatedAt', 'updated_at']))
  }
}

/** 分块明细响应：条目信息 + 当前页分块 */
export function normalizeKnowledgeChunks(raw) {
  const data = asObject(raw)
  let source = []
  for (const key of ['items', 'content', 'chunks', 'data']) {
    if (Array.isArray(data[key])) {
      source = data[key]
      break
    }
  }
  const items = source
    .filter((item) => item && typeof item === 'object' && !Array.isArray(item))
    .map(normalizeKnowledgeChunk)
  return {
    entryId: toNumber(pick(data, ['entryId', 'entry_id', 'knowledgeId'])),
    title: toText(pick(data, ['title', 'name'])),
    indexStatus: toText(pick(data, ['indexStatus', 'index_status'])),
    indexError: toText(pick(data, ['indexError', 'index_error'])),
    indexedAt: toText(pick(data, ['indexedAt', 'indexed_at'])),
    embeddingHash: toText(pick(data, ['embeddingHash', 'embedding_hash', 'indexHash'])),
    embeddingModel: toText(pick(data, ['embeddingModel', 'embedding_model', 'model'])),
    embeddingDimensions: toNumber(
      pick(data, ['embeddingDimensions', 'embedding_dimensions', 'dimensions', 'dimension'])
    ),
    // Spring Page 形态：items 缺失时用 content，total 用 totalElements
    total: toNumber(pick(data, ['total', 'totalElements', 'totalCount'])) ?? items.length,
    page: toNumber(pick(data, ['page', 'number', 'pageNumber'])) ?? 0,
    size: toNumber(pick(data, ['size', 'pageSize'])) ?? items.length,
    items
  }
}

/** 哈希短展示：取前 len 位（默认 8），完整值由调用方放进 title 提示 */
export function shortHash(hash, len = 8) {
  const text = toText(hash)
  if (!text) return ''
  const size = toNumber(len)
  if (size === null || size <= 0) return text
  return text.slice(0, size)
}

/** 向量模型 / 维度展示：'model · 1024'；两者都缺失时返回空串，由界面显示占位 */
export function embeddingLabel(model, dim) {
  const name = toText(model)
  const dimensions = toNumber(dim)
  const parts = []
  if (name) parts.push(name)
  if (dimensions !== null) parts.push(String(dimensions))
  return parts.join(' · ')
}
