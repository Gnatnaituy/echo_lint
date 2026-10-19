/**
 * Batch 4a：API 密钥 / 知识库归一化 + 审计文案单测
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AUDIT_ACTION_OPTIONS, auditActionLabel } from '../src/constants.js'
import {
  createdApiKeyText,
  maskedApiKey,
  normalizeApiKey,
  normalizeApiKeyList
} from '../src/utils/apiKeys.js'
import {
  KNOWLEDGE_CATEGORIES,
  knowledgeTagsPayload,
  normalizeKnowledgeEntry,
  normalizeKnowledgePage
} from '../src/utils/knowledge.js'

/* ---------- API 密钥 ---------- */

test('API 密钥：列表项归一化（只含掩码，无明文）', () => {
  const item = normalizeApiKey({
    id: 3,
    name: '工单系统',
    keyPrefix: 'elk_ab12',
    last4: 'cd34',
    enabled: true,
    createdBy: 'admin',
    createdAt: '2026-10-20T09:00:00',
    lastUsedAt: '2026-10-20T10:00:00'
  })
  assert.deepEqual(item, {
    id: 3,
    name: '工单系统',
    prefix: 'elk_ab12',
    last4: 'cd34',
    key: '',
    enabled: true,
    createdBy: 'admin',
    createdAt: '2026-10-20T09:00:00',
    lastUsedAt: '2026-10-20T10:00:00'
  })
})

test('API 密钥：兼容 prefix/suffix 别名、创建响应的一次性明文', () => {
  const created = normalizeApiKey({
    id: 8,
    name: 'CRM',
    key: 'elk_abcdefghijklmnop',
    prefix: 'elk_ab12',
    enabled: 'true',
    created_at: '2026-10-20T09:00:00',
    last_used_at: '2026-10-20T10:00:00'
  })
  assert.equal(created.key, 'elk_abcdefghijklmnop')
  assert.equal(created.prefix, 'elk_ab12')
  assert.equal(created.enabled, true)
  assert.equal(created.createdAt, '2026-10-20T09:00:00')
  assert.equal(created.lastUsedAt, '2026-10-20T10:00:00')
  assert.equal(normalizeApiKey({ keySuffix: 'wxyz' }).last4, 'wxyz')
  assert.equal(normalizeApiKey(null).id, null)
  assert.equal(normalizeApiKey(null).enabled, null)
})

test('API 密钥列表：裸数组 / Spring Page / content 包装归一化', () => {
  const fromArray = normalizeApiKeyList([
    { id: 1, name: 'A' },
    { id: 2, name: 'B' }
  ])
  assert.equal(fromArray.items.length, 2)
  assert.equal(fromArray.total, 2)

  const fromPage = normalizeApiKeyList({
    content: [{ id: 1, name: 'A' }],
    totalElements: 9,
    size: 10,
    number: 0
  })
  assert.equal(fromPage.total, 9)
  assert.equal(fromPage.items.length, 1)

  assert.equal(normalizeApiKeyList({ items: [{ id: 1 }] }).total, 1)
  assert.equal(normalizeApiKeyList({ keys: [{ id: 1 }] }).total, 1)
  assert.deepEqual(normalizeApiKeyList(null).items, [])
  assert.deepEqual(normalizeApiKeyList('oops').items, [])
})

test('API 密钥掩码：前缀 + 末 4 位，缺字段安全兜底', () => {
  assert.equal(maskedApiKey({ prefix: 'elk_ab12', last4: 'cd34' }), 'elk_ab12…cd34')
  assert.equal(maskedApiKey({ prefix: 'elk_ab12' }), 'elk_ab12…')
  assert.equal(maskedApiKey({ last4: 'cd34' }), '…cd34')
  assert.equal(maskedApiKey({}), '—')
  assert.equal(maskedApiKey(null), '—')
  assert.equal(createdApiKeyText({ key: 'elk_x' }), 'elk_x')
  assert.equal(createdApiKeyText(null), '')
})

/* ---------- 知识库 ---------- */

test('知识库条目：契约字段 + tags 逗号串转数组', () => {
  const entry = normalizeKnowledgeEntry({
    id: 5,
    title: '催收话术红线',
    category: '话术规范',
    violationType: 'THREAT',
    tags: '催收, 威胁 ,催收',
    content: '不得使用威胁性语言',
    enabled: true,
    createdBy: 'admin',
    createdAt: '2026-10-20T09:00:00',
    updatedAt: '2026-10-20T10:00:00'
  })
  assert.deepEqual(entry, {
    id: 5,
    title: '催收话术红线',
    category: '话术规范',
    violationType: 'THREAT',
    tags: ['催收', '威胁'],
    content: '不得使用威胁性语言',
    enabled: true,
    createdBy: 'admin',
    createdAt: '2026-10-20T09:00:00',
    updatedAt: '2026-10-20T10:00:00'
  })
})

test('知识库条目：snake_case / 字符串布尔 / 缺字段兜底', () => {
  const entry = normalizeKnowledgeEntry({
    entryId: '7',
    name: 'SOP',
    violation_type: 'INSULT',
    tagList: ['a', 'b'],
    body: '正文',
    enabled: 'false',
    created_by: 'reviewer',
    updated_at: '2026-10-20 10:00:00'
  })
  assert.equal(entry.id, 7)
  assert.equal(entry.title, 'SOP')
  assert.equal(entry.violationType, 'INSULT')
  assert.deepEqual(entry.tags, ['a', 'b'])
  assert.equal(entry.content, '正文')
  assert.equal(entry.enabled, false)
  assert.equal(entry.createdBy, 'reviewer')
  assert.equal(entry.updatedAt, '2026-10-20 10:00:00')

  const empty = normalizeKnowledgeEntry(null)
  assert.equal(empty.id, null)
  assert.equal(empty.enabled, null)
  assert.deepEqual(empty.tags, [])
})

test('知识库分页：Spring Page / 裸数组 / 空响应', () => {
  const page = normalizeKnowledgePage({
    content: [{ id: 1, title: 'A' }, { id: 2, title: 'B' }],
    totalElements: 12,
    totalPages: 2,
    number: 0,
    size: 10
  })
  assert.equal(page.items.length, 2)
  assert.equal(page.total, 12)
  assert.equal(page.totalPages, 2)
  assert.equal(page.page, 0)
  assert.equal(page.size, 10)

  assert.equal(normalizeKnowledgePage([{ id: 1 }]).total, 1)
  assert.equal(normalizeKnowledgePage({ items: [{ id: 1 }] }).total, 1)
  assert.deepEqual(normalizeKnowledgePage(null), {
    items: [],
    total: 0,
    page: 0,
    size: 0,
    totalPages: 0
  })
})

test('知识库标签：编辑表单数组 -> 逗号分隔 payload', () => {
  assert.equal(knowledgeTagsPayload([' 催收 ', '威胁', '催收', '']), '催收,威胁')
  assert.equal(knowledgeTagsPayload('a, b ,a'), 'a,b')
  assert.equal(knowledgeTagsPayload([]), '')
  assert.equal(knowledgeTagsPayload(null), '')
  assert.ok(KNOWLEDGE_CATEGORIES.length >= 3)
  assert.ok(KNOWLEDGE_CATEGORIES.every((c) => typeof c === 'string' && c))
})

/* ---------- 审计文案 ---------- */

test('审计动作：API 密钥 / 知识库 / 开放 API 上传均有中文文案', () => {
  assert.equal(auditActionLabel('API_KEY_CREATE'), '创建 API 密钥')
  assert.equal(auditActionLabel('API_KEY_REVOKE'), '停用 API 密钥')
  assert.equal(auditActionLabel('KNOWLEDGE_CREATE'), '新增知识库条目')
  assert.equal(auditActionLabel('KNOWLEDGE_UPDATE'), '更新知识库条目')
  assert.equal(auditActionLabel('KNOWLEDGE_DELETE'), '删除知识库条目')
  assert.equal(auditActionLabel('OPEN_API_UPLOAD'), '开放 API 上传录音')
  for (const action of [
    'API_KEY_CREATE',
    'API_KEY_REVOKE',
    'KNOWLEDGE_CREATE',
    'KNOWLEDGE_UPDATE',
    'KNOWLEDGE_DELETE'
  ]) {
    assert.ok(AUDIT_ACTION_OPTIONS.some((opt) => opt.value === action), action)
  }
})
