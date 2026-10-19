/**
 * 知识库条目归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 4a）：
 * - GET /api/knowledge?keyword=&category=&enabled=&page=&size= （登录可读，Spring Page）
 * - POST / PUT / DELETE /api/knowledge[/{id}]（ADMIN + REVIEWER）
 * 字段：{id,title,category,violationType,tags,content,enabled,createdBy,createdAt,updatedAt}
 * tags 后端以逗号分隔字符串存储，这里统一归一化为字符串数组供界面展示/编辑。
 */

import { asObject, pick, toBoolean, toNumber, toText, toStringList } from './shape.js'

/** 常见知识库分类：仅作为下拉候选，允许输入自定义分类 */
export const KNOWLEDGE_CATEGORIES = ['话术规范', '违规示例', '处罚标准', 'SOP', '其他']

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
    updatedAt: toText(pick(data, ['updatedAt', 'updated_at']))
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
