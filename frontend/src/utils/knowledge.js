/**
 * 知识库条目归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 4a / docs/rag-contract.md Batch 6）：
 * - GET /api/knowledge?keyword=&category=&enabled=&page=&size= （登录可读，Spring Page）
 * - POST / PUT / DELETE /api/knowledge[/{id}]（ADMIN + REVIEWER）
 * 字段：{id,title,category,violationType,tags,content,enabled,createdBy,createdAt,updatedAt}
 * tags 后端以逗号分隔字符串存储，这里统一归一化为字符串数组供界面展示/编辑。
 * RAG 索引字段（Batch 6）：indexStatus/indexError/indexedAt/sourceType/sourceFilename/
 * sourceSize/chunkCount，缺失时为 null / ''，由界面显示占位，不臆造状态。
 */

import { asObject, pick, toBoolean, toNumber, toText, toStringList } from './shape.js'

/**
 * 常见知识库分类：仅作为下拉候选，允许输入自定义分类。
 *
 * 注意：这些中文值是持久化数据（写入后端 category 字段），必须保留原值，不能翻译；
 * 展示层通过 `knowledgeCategoryMessageKey` 映射到 messages/knowledge.js 的文案。
 */
export const KNOWLEDGE_CATEGORIES = ['话术规范', '违规示例', '处罚标准', 'SOP', '其他'] // i18n-audit-ignore

/** 分类候选对应的展示文案 key（与 KNOWLEDGE_CATEGORIES 顺序一一对应） */
const KNOWLEDGE_CATEGORY_MESSAGE_KEYS = [
  'knowledge.category.script',
  'knowledge.category.violationExample',
  'knowledge.category.penalty',
  'knowledge.category.sop',
  'knowledge.category.other'
]

/** 分类展示文案 key：自定义分类返回空串，由调用方原样展示 */
export function knowledgeCategoryMessageKey(category) {
  const index = KNOWLEDGE_CATEGORIES.indexOf(toText(category))
  return index >= 0 ? KNOWLEDGE_CATEGORY_MESSAGE_KEYS[index] : ''
}

/** 单条知识库条目 */
export function normalizeKnowledgeEntry(raw) {
  const data = asObject(raw)
  return {
    id: toNumber(pick(data, ['id', 'entryId'])),
    title: toText(pick(data, ['title', 'name'])),
    category: toText(pick(data, ['category', 'type'])),
    violationType: toText(pick(data, ['violationType', 'violation_type', 'violationTypeCode'])),
    tags: toStringList(pick(data, ['tags', 'tagList'])),
    content: toText(pick(data, ['content', 'body', 'text'])),
    enabled: toBoolean(pick(data, ['enabled'])),
    createdBy: toText(pick(data, ['createdBy', 'created_by', 'creator'])),
    createdAt: toText(pick(data, ['createdAt', 'created_at'])),
    updatedAt: toText(pick(data, ['updatedAt', 'updated_at'])),
    // RAG 索引状态（Batch 6）：后端驼峰字段，兼容 snake_case
    indexStatus: toText(pick(data, ['indexStatus', 'index_status'])),
    indexError: toText(pick(data, ['indexError', 'index_error'])),
    indexedAt: toText(pick(data, ['indexedAt', 'indexed_at'])),
    sourceType: toText(pick(data, ['sourceType', 'source_type'])),
    sourceFilename: toText(pick(data, ['sourceFilename', 'source_filename', 'filename'])),
    sourceSize: toNumber(pick(data, ['sourceSize', 'source_size'])),
    chunkCount: toNumber(pick(data, ['chunkCount', 'chunk_count']))
  }
}

/** 列表归一化：Spring Page（content/totalElements/number/size/totalPages）或裸数组 */
export function normalizeKnowledgePage(raw) {
  if (Array.isArray(raw)) {
    const items = raw.map(normalizeKnowledgeEntry)
    return { items, total: items.length, page: 0, size: items.length, totalPages: 0 }
  }
  const data = asObject(raw)
  let source = []
  for (const key of ['content', 'items', 'entries', 'data']) {
    if (Array.isArray(data[key])) {
      source = data[key]
      break
    }
  }
  const items = source.map(normalizeKnowledgeEntry)
  return {
    items,
    total: toNumber(pick(data, ['totalElements', 'total', 'totalCount'])) ?? items.length,
    page: toNumber(pick(data, ['number', 'page', 'pageNumber'])) ?? 0,
    size: toNumber(pick(data, ['size', 'pageSize'])) ?? items.length,
    totalPages: toNumber(pick(data, ['totalPages', 'pages'])) ?? 0
  }
}

/** 编辑表单 -> 后端 tags 字段（逗号分隔字符串） */
export function knowledgeTagsPayload(tags) {
  return toStringList(tags).join(',')
}
