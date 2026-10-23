/**
 * 索引明细页：分块接口归一化 + 页面注册静态守卫。
 *
 * 归一化契约见 src/utils/knowledgeIndex.js（后端 GET /api/knowledge/{id}/chunks）；
 * 静态守卫覆盖路由 / 侧栏 / i18n 登记，避免页面「写好了但进不去 / 没文案」。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'
import {
  embeddingLabel,
  normalizeKnowledgeChunk,
  normalizeKnowledgeChunks,
  shortHash
} from '../src/utils/knowledgeIndex.js'
import knowledgeIndexMessages from '../src/i18n/messages/knowledgeIndex.js'
import baseMessages from '../src/i18n/messages/base.js'
import { mergeMessages } from '../src/i18n/messages/index.js'
import { messageKeys } from '../src/i18n/index.js'

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)))
const readSource = (relativePath) => readFileSync(join(ROOT, relativePath), 'utf8')

/* ---------- normalizeKnowledgeChunks：完整契约 ---------- */

test('分块明细：完整契约字段归一化', () => {
  const data = normalizeKnowledgeChunks({
    entryId: 12,
    title: '催收话术规范.pdf',
    indexStatus: 'READY',
    indexError: null,
    indexedAt: '2026-10-23T12:00:00',
    embeddingHash: '3f2a9c1d8b7e6f504132a1b2c3d4e5f6',
    embeddingModel: 'qwen3.7-text-embedding',
    embeddingDimensions: 1024,
    total: 7,
    page: 0,
    size: 10,
    items: [
      {
        chunkId: 1,
        chunkIndex: 0,
        content: '第一条分块内容',
        contentHash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        embeddingModel: 'qwen3.7-text-embedding',
        embeddingDim: 1024,
        createdAt: '2026-10-23T12:00:00',
        updatedAt: '2026-10-23T12:00:01'
      }
    ]
  })
  assert.deepEqual(data, {
    entryId: 12,
    title: '催收话术规范.pdf',
    indexStatus: 'READY',
    indexError: '',
    indexedAt: '2026-10-23T12:00:00',
    embeddingHash: '3f2a9c1d8b7e6f504132a1b2c3d4e5f6',
    embeddingModel: 'qwen3.7-text-embedding',
    embeddingDimensions: 1024,
    total: 7,
    page: 0,
    size: 10,
    items: [
      {
        chunkId: 1,
        chunkIndex: 0,
        content: '第一条分块内容',
        contentHash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        embeddingModel: 'qwen3.7-text-embedding',
        embeddingDim: 1024,
        createdAt: '2026-10-23T12:00:00',
        updatedAt: '2026-10-23T12:00:01'
      }
    ]
  })
})

test('分块明细：兼容 snake_case 别名与 Spring Page 形态', () => {
  const data = normalizeKnowledgeChunks({
    entry_id: '12',
    title: '制度手册.pdf',
    index_status: 'FAILED',
    index_error: '解析超时',
    indexed_at: '2026-10-23T12:00:00',
    embedding_hash: 'deadbeef',
    embedding_model: 'bge-m3',
    embedding_dimensions: '1024',
    content: [
      {
        chunk_id: '5',
        chunk_index: '3',
        content: '分块正文',
        content_hash: 'cafebabe',
        embedding_model: 'bge-m3',
        embedding_dim: '1024',
        created_at: '2026-10-23T12:00:00',
        updated_at: '2026-10-23T12:00:02'
      }
    ],
    totalElements: '7',
    number: '1',
    size: '10'
  })
  assert.equal(data.entryId, 12)
  assert.equal(data.indexStatus, 'FAILED')
  assert.equal(data.indexError, '解析超时')
  assert.equal(data.embeddingHash, 'deadbeef')
  assert.equal(data.embeddingModel, 'bge-m3')
  assert.equal(data.embeddingDimensions, 1024)
  assert.equal(data.total, 7)
  assert.equal(data.page, 1)
  assert.equal(data.size, 10)
  assert.equal(data.items.length, 1)
  assert.equal(data.items[0].chunkId, 5)
  assert.equal(data.items[0].chunkIndex, 3)
  assert.equal(data.items[0].embeddingDim, 1024)
  assert.equal(data.items[0].updatedAt, '2026-10-23T12:00:02')
})

test('分块明细：null / 非对象入参安全兜底，items 过滤非法项', () => {
  const empty = normalizeKnowledgeChunks(null)
  assert.deepEqual(empty, {
    entryId: null,
    title: '',
    indexStatus: '',
    indexError: '',
    indexedAt: '',
    embeddingHash: '',
    embeddingModel: '',
    embeddingDimensions: null,
    total: 0,
    page: 0,
    size: 0,
    items: []
  })
  assert.deepEqual(normalizeKnowledgeChunks('oops'), empty)
  assert.deepEqual(normalizeKnowledgeChunks([]), empty)

  const filtered = normalizeKnowledgeChunks({
    items: [null, 'x', 42, [], { chunkId: 9 }]
  })
  assert.equal(filtered.items.length, 1)
  assert.equal(filtered.items[0].chunkId, 9)
  assert.equal(filtered.items[0].chunkIndex, null)
  assert.equal(filtered.items[0].content, '')
  assert.equal(filtered.items[0].contentHash, '')

  // total 缺失时回退当前页条数
  assert.equal(normalizeKnowledgeChunks({ items: [{ chunkId: 1 }, { chunkId: 2 }] }).total, 2)
})

test('单条分块：缺字段不臆造（null / 空串）', () => {
  assert.deepEqual(normalizeKnowledgeChunk(null), {
    chunkId: null,
    chunkIndex: null,
    content: '',
    contentHash: '',
    embeddingModel: '',
    embeddingDim: null,
    createdAt: '',
    updatedAt: ''
  })
})

/* ---------- 展示辅助 ---------- */

test('shortHash：默认 8 位、短值原样、空值空串、非法长度回退完整值', () => {
  assert.equal(shortHash('abcdef1234567890'), 'abcdef12')
  assert.equal(shortHash('abcdef1234567890', 4), 'abcd')
  assert.equal(shortHash('abc'), 'abc')
  assert.equal(shortHash(''), '')
  assert.equal(shortHash(null), '')
  assert.equal(shortHash('abcdef', 0), 'abcdef')
  assert.equal(shortHash('abcdef', 'x'), 'abcdef')
  assert.equal(shortHash('abcdef', '4'), 'abcd')
})

test('embeddingLabel：模型 · 维度，缺一侧时只显示另一侧', () => {
  assert.equal(embeddingLabel('bge-m3', 1024), 'bge-m3 · 1024')
  assert.equal(embeddingLabel('bge-m3', '1024'), 'bge-m3 · 1024')
  assert.equal(embeddingLabel('', 1024), '1024')
  assert.equal(embeddingLabel('bge-m3', null), 'bge-m3')
  assert.equal(embeddingLabel(null, null), '')
})

/* ---------- i18n 模块 ---------- */

test('索引明细文案：zh / en key 一一对应且使用 knowledgeIndex 前缀', () => {
  const zhKeys = messageKeys(knowledgeIndexMessages.zh)
  const enKeys = messageKeys(knowledgeIndexMessages.en)
  assert.deepEqual(enKeys, zhKeys)
  assert.ok(zhKeys.length > 30)
  assert.ok(
    zhKeys.every((key) => key.startsWith('knowledgeIndex.')),
    `存在未使用 knowledgeIndex 前缀的 key：${zhKeys.filter((key) => !key.startsWith('knowledgeIndex.')).join(', ')}`
  )
  // 每个 value 都非空
  for (const [key, value] of Object.entries(knowledgeIndexMessages.zh)) {
    assert.equal(typeof value, 'string', `zh.${key} 必须是字符串`)
    assert.ok(value.trim().length > 0, `zh.${key} 不能为空`)
  }
})

test('索引明细文案：已登记到消息注册表，复用既有 meta / knowledge key', () => {
  const zh = mergeMessages('zh')
  const en = mergeMessages('en')
  assert.equal(zh['knowledgeIndex.pageTitle'], '索引明细')
  assert.equal(en['knowledgeIndex.pageTitle'], 'Index details')
  // 复用的既有 key 仍在词典里（页面直接引用，不能删）
  for (const key of [
    'meta.indexStatus.PENDING',
    'meta.indexStatus.READY',
    'meta.indexStatus.FAILED',
    'knowledge.sourceDocument',
    'knowledge.sourceManual',
    'knowledge.chunkCount',
    'knowledge.indexedAt',
    'knowledge.untitledEntry'
  ]) {
    assert.ok(key in zh, `zh 缺少复用的 key：${key}`)
    assert.ok(key in en, `en 缺少复用的 key：${key}`)
  }
})

test('文案：索引明细不再占用侧栏 key，页内 Tab 文案落在 knowledge 域', () => {
  // 侧栏入口已移除：base.js 恢复原样，不再有 nav.knowledgeIndex
  assert.ok(!('nav.knowledgeIndex' in baseMessages.zh), 'zh 不应再有 nav.knowledgeIndex')
  assert.ok(!('nav.knowledgeIndex' in baseMessages.en), 'en 不应再有 nav.knowledgeIndex')

  // Tab 容器 aria-label（knowledge.tabs.label）与两个 Tab 标题（页面标题 key）均需有中英文案
  const zh = mergeMessages('zh')
  const en = mergeMessages('en')
  assert.equal(zh['knowledge.tabs.label'], '知识库视图')
  assert.equal(en['knowledge.tabs.label'], 'Knowledge base views')
  assert.equal(zh['knowledge.pageTitle'], '知识库 / SOP')
  assert.equal(zh['knowledgeIndex.pageTitle'], '索引明细')
  for (const key of ['knowledge.tabs.label', 'knowledge.pageTitle', 'knowledgeIndex.pageTitle']) {
    assert.ok(key in en, `en 缺少 key：${key}`)
  }
})

/* ---------- 静态守卫：路由 / 侧栏 / 页内 Tab / API / 页面 ---------- */

test('路由：/knowledge-index 重定向到 /knowledge?tab=index，不再直接加载页面', () => {
  const router = readSource('src/router/index.js')
  const start = router.indexOf("path: '/knowledge-index'")
  assert.ok(start >= 0, '未找到 /knowledge-index 路由（旧地址需保留重定向）')

  const blockEnd = router.indexOf('\n  },', start)
  assert.ok(blockEnd > start, '无法截取 /knowledge-index 路由配置')
  const block = router.slice(start, blockEnd)
  assert.match(
    block,
    /redirect:\s*\{\s*path:\s*'\/knowledge',\s*query:\s*\{\s*tab:\s*'index'\s*\}\s*\}/,
    '/knowledge-index 必须重定向到 /knowledge?tab=index'
  )
  assert.ok(!/component\s*:/.test(block), '/knowledge-index 不应再声明 component')
  assert.ok(!/roles\s*:/.test(block), '/knowledge-index 重定向不再做路由级 roles 限定')
  assert.ok(!/KnowledgeIndex\.vue/.test(router), 'router 不应再 import KnowledgeIndex.vue')

  // 容器页路由仍然存在且加载 Knowledge.vue
  assert.match(
    router,
    /path:\s*'\/knowledge',\s*\n\s*component:\s*\(\)\s*=>\s*import\('\.\.\/views\/Knowledge\.vue'\)/
  )
  const anchor = router.indexOf("path: '/knowledge'")
  const fallback = router.indexOf("path: '/:pathMatch(.*)*'")
  assert.ok(anchor >= 0, '未找到 /knowledge 路由')
  assert.ok(start > anchor, '/knowledge-index 必须排在 /knowledge 之后')
  assert.ok(fallback > start, '/knowledge-index 必须排在兜底路由之前')
})

test('侧栏：知识组只剩词典 / 语料 / 知识库三项，不再挂索引明细入口', () => {
  const sidebar = readSource('src/components/AppSidebar.vue')
  assert.ok(!sidebar.includes('/knowledge-index'), '侧栏不应再有 /knowledge-index 入口')
  assert.ok(!sidebar.includes('nav.knowledgeIndex'), '侧栏不应再引用 nav.knowledgeIndex')

  const group = sidebar.match(
    /titleKey:\s*'nav\.group\.knowledge',\s*items:\s*\[([\s\S]*?)\]/
  )
  assert.ok(group, '未找到知识组配置')
  const paths = [...group[1].matchAll(/path:\s*'([^']+)'/g)].map((match) => match[1])
  assert.deepEqual(paths, ['/dictionary', '/corpus', '/knowledge'])
})

/* ---------- 静态守卫：知识库页内 Tab ---------- */

/** 取单文件组件模板根元素的标签名与 class（避免整份源码正则误伤 script / style） */
function templateRoot(source) {
  const match = source.match(/^<template>\s*<([a-zA-Z][\w-]*)([^>]*)>/)
  assert.ok(match, '未找到模板根元素')
  const classMatch = match[2].match(/\bclass="([^"]*)"/)
  return { tag: match[1], className: classMatch ? classMatch[1] : '' }
}

test('知识库页内 Tab：容器挂载两个面板，索引明细 Tab 仅 ADMIN 可见', () => {
  const view = readSource('src/views/Knowledge.vue')

  // 两个面板：条目面板同步引入，索引明细用 defineAsyncComponent 分包懒加载
  assert.match(view, /import KnowledgeEntries from '\.\/KnowledgeEntries\.vue'/)
  assert.match(view, /defineAsyncComponent\(\(\) => import\('\.\/KnowledgeIndex\.vue'\)\)/)
  assert.ok(
    !/import KnowledgeIndex from/.test(view),
    'KnowledgeIndex 应保持异步分包，不能静态 import（非管理员不下载索引明细代码）'
  )
  assert.match(view, /v-if="activeTab === 'entries'"/)
  assert.match(view, /v-else-if="activeTab === 'index'"/)
  assert.match(view, /role="tabpanel"/)

  // 无障碍 Tab 语义：tablist + 两个 tab + aria-selected
  assert.match(view, /role="tablist"/)
  assert.equal((view.match(/role="tab"/g) || []).length, 2, '应有两个 role="tab" 按钮')
  assert.match(view, /:aria-selected="activeTab === 'entries' \? 'true' : 'false'"/)
  assert.match(view, /:aria-selected="activeTab === 'index' \? 'true' : 'false'"/)

  // 索引明细 Tab 由 ADMIN 角色控制（与后端分块接口鉴权一致）
  assert.match(view, /v-if="canViewIndex"/)
  assert.match(view, /const canViewIndex = computed\(\(\) => auth\.hasRole\('ADMIN'\)\)/)

  // Tab 标题复用两个页面标题；tablist 有可读 aria-label
  assert.match(view, /t\('knowledge\.pageTitle'\)/)
  assert.match(view, /t\('knowledgeIndex\.pageTitle'\)/)
  assert.match(view, /:aria-label="t\('knowledge\.tabs\.label'\)"/)

  // 切换 Tab 写回 URL query.tab：深链 /knowledge?tab=index 可还原视图
  assert.match(view, /route\.query\.tab === 'index'/)
  assert.match(view, /await router\.replace\(\{ query \}\)/)
  assert.match(view, /if \(tab === 'index'\) query\.tab = 'index'/)
  assert.match(view, /delete query\.tab/)
})

test('知识库面板：容器承载 page 外壳，两个 Tab 面板根节点只有 stack', () => {
  const container = templateRoot(readSource('src/views/Knowledge.vue'))
  const containerClasses = container.className.split(/\s+/).filter(Boolean)
  assert.ok(containerClasses.includes('page'), '容器页应承载唯一的 page 外壳')
  assert.ok(containerClasses.includes('stack'), '容器页应使用 stack 布局')

  for (const file of ['src/views/KnowledgeEntries.vue', 'src/views/KnowledgeIndex.vue']) {
    const root = templateRoot(readSource(file))
    const classes = root.className.split(/\s+/).filter(Boolean)
    assert.ok(classes.includes('stack'), `${file} 根节点应保留 stack 布局`)
    assert.ok(!classes.includes('page'), `${file} 根节点不应重复 page 外壳（避免双重内边距）`)
  }
})

test('API 层：列表透传筛选参数，分块接口使用归一化函数', () => {
  const api = readSource('src/api/index.js')
  assert.match(api, /listKnowledgeChunks\(id,\s*\{ page, size \} = \{\}\)/)
  assert.match(api, /\/knowledge\/\$\{encodeURIComponent\(id\)\}\/chunks/)
  assert.match(api, /indexStatus:\s*param\(indexStatus\)/)
  assert.match(api, /sourceType:\s*param\(sourceType\)/)
  assert.match(api, /normalizeKnowledgeChunks/)
})

test('页面源码：使用分块 / 汇总 / 重建接口与 4s 自动刷新，对话框 860px 且 append-to-body', () => {
  const view = readSource('src/views/KnowledgeIndex.vue')
  assert.match(view, /api\.listKnowledgeChunks\(/)
  assert.match(view, /api\.getRagSettings\(/)
  assert.match(view, /api\.reindexKnowledge\(/)
  assert.match(view, /REFRESH_SECONDS = 4/)
  assert.match(view, /document\.hidden/)
  assert.match(view, /append-to-body/)
  assert.match(view, /width="860px"/)
  // 状态筛选来自 INDEX_STATUS_META，不在页面里硬编码枚举
  assert.match(view, /INDEX_STATUS_META/)
})

test('测试脚本：knowledgeIndex.test.mjs 已加入 npm test', () => {
  const pkg = JSON.parse(readSource('package.json'))
  assert.ok(
    String(pkg.scripts?.test || '').includes('tests/knowledgeIndex.test.mjs'),
    'package.json 的 test 脚本未包含 tests/knowledgeIndex.test.mjs'
  )
})
