/**
 * Batch 6 RAG 契约归一化单测：设置 / 连通性测试 / 重索引 / 检索 / 评测 + 索引状态元数据。
 * 后端契约见 docs/rag-contract.md。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { normalizeKnowledgeEntry } from '../src/utils/knowledge.js'
import {
  formatFileSize,
  INDEX_PROGRESS_STATUSES,
  INDEX_STATUS_META,
  indexStatusMeta,
  isIndexInProgress,
  normalizeKnowledgeSearch,
  normalizeRagEvaluation,
  normalizeRagSettings,
  normalizeRagTest,
  normalizeReindexResult
} from '../src/utils/rag.js'

/* ---------- RAG 设置 ---------- */

test('RAG 设置：完整契约字段归一化', () => {
  const settings = normalizeRagSettings({
    enabled: true,
    source: 'database',
    embedding: {
      providerId: 'openai',
      providerLabel: 'OpenAI',
      model: 'text-embedding-3-small',
      path: '/v1/embeddings',
      dimensions: 1536,
      timeoutSeconds: 30,
      apiKeyConfigured: true
    },
    retrieval: { topK: 4, minScore: 0.25, maxContextChars: 2400, corpusExamples: 5 },
    index: {
      knowledgeTotal: 12,
      knowledgeReady: 10,
      knowledgePending: 1,
      knowledgeFailed: 1,
      corpusTotal: 20,
      corpusReady: 18,
      lastReindexAt: '2026-10-20T10:00:00',
      vectorAvailable: true
    }
  })
  assert.deepEqual(settings, {
    enabled: true,
    source: 'database',
    embedding: {
      providerId: 'openai',
      providerLabel: 'OpenAI',
      model: 'text-embedding-3-small',
      path: '/v1/embeddings',
      dimensions: 1536,
      timeoutSeconds: 30,
      apiKeyConfigured: true
    },
    retrieval: { topK: 4, minScore: 0.25, maxContextChars: 2400, corpusExamples: 5 },
    index: {
      knowledgeTotal: 12,
      knowledgeReady: 10,
      knowledgePending: 1,
      knowledgeFailed: 1,
      corpusTotal: 20,
      corpusReady: 18,
      lastReindexAt: '2026-10-20T10:00:00',
      vectorAvailable: true
    }
  })
})

test('RAG 设置：缺字段不臆造（null / 空串 / 空对象兜底）', () => {
  assert.deepEqual(normalizeRagSettings(null), {
    enabled: null,
    source: '',
    embedding: {
      providerId: '',
      providerLabel: '',
      model: '',
      path: '',
      dimensions: null,
      timeoutSeconds: null,
      apiKeyConfigured: null
    },
    retrieval: { topK: null, minScore: null, maxContextChars: null, corpusExamples: null },
    index: {
      knowledgeTotal: null,
      knowledgeReady: null,
      knowledgePending: null,
      knowledgeFailed: null,
      corpusTotal: null,
      corpusReady: null,
      lastReindexAt: '',
      vectorAvailable: null
    }
  })
  // 非对象输入同样安全兜底
  assert.equal(normalizeRagSettings('oops').enabled, null)
  assert.equal(normalizeRagSettings([]).retrieval.topK, null)
})

test('RAG 设置：字符串数字 / 字符串布尔 / 别名兼容', () => {
  const settings = normalizeRagSettings({
    enabled: 'false',
    ragEnabled: true,
    retrieval: { top_k: '6', threshold: '0.4', contextChars: '3200', fewShotExamples: '3' },
    embedding: { provider: 'deepseek', label: 'DeepSeek', model: 'bge-m3', dimension: '1024' },
    index: { knowledge_ready: '9', knowledge_total: '10', vector_available: 'true' }
  })
  assert.equal(settings.enabled, false) // 顶层 enabled 优先
  assert.equal(settings.retrieval.topK, 6)
  assert.equal(settings.retrieval.minScore, 0.4)
  assert.equal(settings.retrieval.maxContextChars, 3200)
  assert.equal(settings.retrieval.corpusExamples, 3)
  assert.equal(settings.embedding.providerId, 'deepseek')
  assert.equal(settings.embedding.providerLabel, 'DeepSeek')
  assert.equal(settings.embedding.dimensions, 1024)
  assert.equal(settings.index.knowledgeReady, 9)
  assert.equal(settings.index.knowledgeTotal, 10)
  assert.equal(settings.index.vectorAvailable, true)
})

/* ---------- 连通性测试 / 重索引 ---------- */

test('Embedding 连通性测试：ok / 延迟 / 维度 / 错误信息归一化', () => {
  assert.deepEqual(
    normalizeRagTest({
      ok: true,
      providerLabel: 'OpenAI',
      model: 'text-embedding-3-small',
      dimensions: 1536,
      latencyMs: 120,
      message: 'ok'
    }),
    {
      ok: true,
      providerLabel: 'OpenAI',
      model: 'text-embedding-3-small',
      dimensions: 1536,
      latencyMs: 120,
      message: 'ok'
    }
  )
  const failed = normalizeRagTest({ success: 'false', latency: '88', detail: '401 unauthorized' })
  assert.equal(failed.ok, false)
  assert.equal(failed.latencyMs, 88)
  assert.equal(failed.message, '401 unauthorized')
  // ok 缺失时为 null：界面按失败展示，不臆造成功
  assert.equal(normalizeRagTest(null).ok, null)
  assert.equal(normalizeRagTest(null).dimensions, null)
})

test('重索引结果：queued / skipped / message 归一化', () => {
  assert.deepEqual(normalizeReindexResult({ queued: 12, skipped: 3, message: '已提交' }), {
    queued: 12,
    skipped: 3,
    message: '已提交'
  })
  const empty = normalizeReindexResult(null)
  assert.equal(empty.queued, null)
  assert.equal(empty.skipped, null)
  assert.equal(empty.message, '')
  assert.equal(normalizeReindexResult({ queuedCount: '5' }).queued, 5)
})

/* ---------- 知识库检索预览 ---------- */

test('知识库检索：query + results 契约字段归一化', () => {
  const search = normalizeKnowledgeSearch({
    query: '催收话术',
    results: [
      {
        chunkId: 1,
        entryId: 2,
        title: '话术规范',
        category: 'SOP',
        content: '不得威胁客户',
        score: 0.83
      },
      { chunkId: '3', entry_id: '4', name: '合规示例', text: '应表明身份', similarity: '0.71' }
    ]
  })
  assert.equal(search.query, '催收话术')
  assert.equal(search.results.length, 2)
  assert.deepEqual(search.results[0], {
    chunkId: 1,
    entryId: 2,
    title: '话术规范',
    category: 'SOP',
    content: '不得威胁客户',
    score: 0.83
  })
  assert.equal(search.results[1].chunkId, 3)
  assert.equal(search.results[1].entryId, 4)
  assert.equal(search.results[1].title, '合规示例')
  assert.equal(search.results[1].content, '应表明身份')
  assert.equal(search.results[1].score, 0.71)
})

test('知识库检索：空结果 / 非数组 results / 非法结果项安全兜底', () => {
  assert.deepEqual(normalizeKnowledgeSearch(null), { query: '', results: [] })
  assert.deepEqual(normalizeKnowledgeSearch({ results: 'oops' }).results, [])
  const filtered = normalizeKnowledgeSearch({ results: [null, 'x', { chunkId: 5 }] })
  assert.equal(filtered.results.length, 1)
  assert.equal(filtered.results[0].chunkId, 5)
  assert.equal(filtered.results[0].entryId, null)
  assert.equal(filtered.results[0].score, null)
})

/* ---------- RAG 效果评测 ---------- */

test('RAG 评测：baseline / rag / delta / cases 完整归一化', () => {
  const evaluation = normalizeRagEvaluation({
    model: 'gpt-4o-mini',
    sampleSize: 10,
    baseline: {
      evaluated: 10,
      correct: 7,
      failed: 0,
      accuracy: 0.7,
      precision: 0.8,
      recall: 0.6,
      tp: 3,
      fp: 1,
      tn: 3,
      fn: 2,
      latencyMs: 1200
    },
    rag: {
      evaluated: 10,
      correct: 8,
      failed: 1,
      accuracy: 0.8,
      precision: 0.8,
      recall: 0.8,
      tp: 4,
      fp: 1,
      tn: 3,
      fn: 1,
      latencyMs: 1500
    },
    delta: { accuracy: 0.1, precision: 0, recall: 0.2 },
    cases: [
      {
        id: 1,
        label: 'VIOLATION',
        baselinePrediction: 'VIOLATION',
        ragPrediction: 'VIOLATION',
        baselineConfidence: 0.9,
        ragConfidence: 0.95,
        refs: ['话术规范 A'],
        error: null
      },
      {
        caseId: '2',
        expected: 'COMPLIANT',
        baseline: 'VIOLATION',
        rag: 'COMPLIANT',
        baselineScore: '0.6',
        ragScore: '0.88',
        references: '话术规范 A, 合规示例 B',
        errorMessage: ''
      }
    ]
  })
  assert.equal(evaluation.model, 'gpt-4o-mini')
  assert.equal(evaluation.sampleSize, 10)
  assert.equal(evaluation.baseline.accuracy, 0.7)
  assert.equal(evaluation.baseline.tp, 3)
  assert.equal(evaluation.baseline.latencyMs, 1200)
  assert.equal(evaluation.rag.accuracy, 0.8)
  assert.equal(evaluation.rag.failed, 1)
  assert.deepEqual(evaluation.delta, { accuracy: 0.1, precision: 0, recall: 0.2 })
  assert.equal(evaluation.cases.length, 2)
  assert.deepEqual(evaluation.cases[0], {
    id: 1,
    label: 'VIOLATION',
    baselinePrediction: 'VIOLATION',
    ragPrediction: 'VIOLATION',
    baselineConfidence: 0.9,
    ragConfidence: 0.95,
    refs: ['话术规范 A'],
    error: ''
  })
  assert.equal(evaluation.cases[1].id, 2)
  assert.equal(evaluation.cases[1].baselineConfidence, 0.6)
  assert.equal(evaluation.cases[1].ragConfidence, 0.88)
  assert.deepEqual(evaluation.cases[1].refs, ['话术规范 A', '合规示例 B'])
})

test('RAG 评测：缺字段安全兜底（指标 null、cases 空数组）', () => {
  const empty = normalizeRagEvaluation(null)
  assert.equal(empty.model, '')
  assert.equal(empty.sampleSize, null)
  assert.equal(empty.baseline.accuracy, null)
  assert.equal(empty.rag.precision, null)
  assert.equal(empty.delta.accuracy, null)
  assert.deepEqual(empty.cases, [])
  assert.deepEqual(normalizeRagEvaluation({ cases: 'oops' }).cases, [])
  assert.deepEqual(normalizeRagEvaluation({ cases: [null, 3, { id: 9 }] }).cases[0].id, 9)
})

/* ---------- 知识库条目索引字段 ---------- */

test('知识库条目：RAG 索引字段（后端驼峰）归一化', () => {
  const entry = normalizeKnowledgeEntry({
    id: 5,
    title: '话术规范.pdf',
    indexStatus: 'READY',
    indexError: '',
    indexedAt: '2026-10-20T10:00:00',
    sourceType: 'DOCUMENT',
    sourceFilename: '话术规范.pdf',
    sourceSize: 2048,
    chunkCount: 12
  })
  assert.equal(entry.indexStatus, 'READY')
  assert.equal(entry.indexError, '')
  assert.equal(entry.indexedAt, '2026-10-20T10:00:00')
  assert.equal(entry.sourceType, 'DOCUMENT')
  assert.equal(entry.sourceFilename, '话术规范.pdf')
  assert.equal(entry.sourceSize, 2048)
  assert.equal(entry.chunkCount, 12)
})

test('知识库条目：索引字段 snake_case / 字符串数字兼容，缺失兜底', () => {
  const entry = normalizeKnowledgeEntry({
    id: 6,
    index_status: 'FAILED',
    index_error: '解析失败：文件损坏',
    indexed_at: '2026-10-20T11:00:00',
    source_type: 'DOCUMENT',
    source_size: '10240',
    chunk_count: '3'
  })
  assert.equal(entry.indexStatus, 'FAILED')
  assert.equal(entry.indexError, '解析失败：文件损坏')
  assert.equal(entry.indexedAt, '2026-10-20T11:00:00')
  assert.equal(entry.sourceType, 'DOCUMENT')
  assert.equal(entry.sourceSize, 10240)
  assert.equal(entry.chunkCount, 3)

  const empty = normalizeKnowledgeEntry(null)
  assert.equal(empty.indexStatus, '')
  assert.equal(empty.indexError, '')
  assert.equal(empty.sourceFilename, '')
  assert.equal(empty.sourceSize, null)
  assert.equal(empty.chunkCount, null)
})

/* ---------- 索引状态元数据 ---------- */

test('索引状态：PENDING / PARSING / INDEXING / READY / FAILED 中文标签与颜色', () => {
  assert.deepEqual(INDEX_STATUS_META.PENDING, { label: '待处理', type: 'warn' })
  assert.deepEqual(INDEX_STATUS_META.PARSING, { label: '解析中', type: 'warn' })
  assert.deepEqual(INDEX_STATUS_META.INDEXING, { label: '索引中', type: 'warn' })
  assert.deepEqual(INDEX_STATUS_META.READY, { label: '已就绪', type: 'ok' })
  assert.deepEqual(INDEX_STATUS_META.FAILED, { label: '失败', type: 'danger' })

  assert.deepEqual(indexStatusMeta('READY'), { label: '已就绪', type: 'ok' })
  assert.deepEqual(indexStatusMeta('failed'), { label: '失败', type: 'danger' })
  // 未知值回退原值，不臆造中文
  assert.deepEqual(indexStatusMeta('ROLLING'), { label: 'ROLLING', type: 'info' })
  assert.deepEqual(indexStatusMeta(''), { label: '—', type: 'info' })
  assert.deepEqual(indexStatusMeta(null), { label: '—', type: 'info' })
})

test('索引状态：处理中判定与自动刷新状态列表', () => {
  assert.deepEqual(INDEX_PROGRESS_STATUSES, ['PENDING', 'PARSING', 'INDEXING'])
  assert.equal(isIndexInProgress('PENDING'), true)
  assert.equal(isIndexInProgress('parsing'), true)
  assert.equal(isIndexInProgress('INDEXING'), true)
  assert.equal(isIndexInProgress('READY'), false)
  assert.equal(isIndexInProgress('FAILED'), false)
  assert.equal(isIndexInProgress(''), false)
  assert.equal(isIndexInProgress(null), false)
})

/* ---------- 展示辅助 ---------- */

test('文件大小格式化：B / KB / MB 与无效值占位', () => {
  assert.equal(formatFileSize(0), '0 B')
  assert.equal(formatFileSize(512), '512 B')
  assert.equal(formatFileSize(2048), '2.0 KB')
  assert.equal(formatFileSize('1048576'), '1.0 MB')
  assert.equal(formatFileSize(5 * 1024 * 1024), '5.0 MB')
  assert.equal(formatFileSize(null), '—')
  assert.equal(formatFileSize(-1), '—')
  assert.equal(formatFileSize('abc'), '—')
})
