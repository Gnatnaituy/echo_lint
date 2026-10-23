/**
 * RAG（检索增强）设置 / 连通性测试 / 检索 / 评测结果归一化。
 *
 * 后端契约（docs/rag-contract.md Batch 6）：
 * - GET  /api/settings/rag -> {enabled, source, embedding:{...}, retrieval:{...}, index:{...}}
 * - PUT  /api/settings/rag（请求 {enabled, topK, minScore, maxContextChars, corpusExamples}，响应同 GET）
 * - POST /api/settings/rag/test -> {ok, providerLabel, model, dimensions, latencyMs, message}
 * - POST /api/settings/rag/reindex {scope} -> {queued, skipped, message}
 * - POST /api/settings/rag/evaluate {sampleSize} ->
 *   {model, sampleSize, baseline, rag, delta:{accuracy,precision,recall}, cases:[...]}
 * - POST /api/knowledge/search {query, topK} -> {query, results:[{chunkId,entryId,title,category,content,score}]}
 *
 * 后端与前端并行开发，字段可能增补 / 暂缺：缺失字段统一返回 null / '' / []，
 * 由界面显示「— / 未知」，不臆造业务默认值。
 */

import { asObject, pick, toBoolean, toNumber, toText, toStringList } from './shape.js'
import { t } from '../i18n/index.js'

/* ---------- 知识库索引状态 ---------- */

/**
 * 索引状态元数据：label 为 getter，读取时按当前语言翻译（key 见 messages/meta.js），
 * 同时保留现有 chip 颜色类型。
 * READY 为成功（ok），FAILED 为危险（danger），处理中为警告（warn）。
 */
export const INDEX_STATUS_META = {
  PENDING: { get label() { return t('meta.indexStatus.PENDING') }, type: 'warn' },
  PARSING: { get label() { return t('meta.indexStatus.PARSING') }, type: 'warn' },
  INDEXING: { get label() { return t('meta.indexStatus.INDEXING') }, type: 'warn' },
  READY: { get label() { return t('meta.indexStatus.READY') }, type: 'ok' },
  FAILED: { get label() { return t('meta.indexStatus.FAILED') }, type: 'danger' }
}

/** 仍在处理中的索引状态：列表据此自动轮询刷新 */
export const INDEX_PROGRESS_STATUSES = ['PENDING', 'PARSING', 'INDEXING']

/** 索引状态展示：已知枚举按当前语言展示，未知值原样回退（空值显示占位） */
export function indexStatusMeta(status) {
  const text = toText(status)
  if (!text) return { label: '—', type: 'info' }
  return INDEX_STATUS_META[text.toUpperCase()] || { label: text, type: 'info' }
}

/** 是否处于解析 / 索引处理中（PENDING / PARSING / INDEXING） */
export function isIndexInProgress(status) {
  return INDEX_PROGRESS_STATUSES.includes(toText(status).toUpperCase())
}

/* ---------- RAG 设置 ---------- */

/** GET / PUT /api/settings/rag 响应归一化 */
export function normalizeRagSettings(raw) {
  const data = asObject(raw)
  const embedding = asObject(pick(data, ['embedding', 'embeddingConfig']))
  const retrieval = asObject(pick(data, ['retrieval', 'retrievalConfig', 'search']))
  const index = asObject(pick(data, ['index', 'indexStats', 'indexStatus']))
  return {
    enabled: toBoolean(pick(data, ['enabled', 'ragEnabled'])),
    source: toText(pick(data, ['source'])),
    embedding: {
      providerId: toText(pick(embedding, ['providerId', 'provider', 'providerKey'])),
      providerLabel: toText(pick(embedding, ['providerLabel', 'label'])),
      model: toText(pick(embedding, ['model'])),
      path: toText(pick(embedding, ['path', 'embeddingPath'])),
      dimensions: toNumber(pick(embedding, ['dimensions', 'dimension'])),
      timeoutSeconds: toNumber(pick(embedding, ['timeoutSeconds', 'timeout'])),
      apiKeyConfigured: toBoolean(pick(embedding, ['apiKeyConfigured', 'keyConfigured']))
    },
    retrieval: {
      topK: toNumber(pick(retrieval, ['topK', 'top_k', 'topk'])),
      minScore: toNumber(pick(retrieval, ['minScore', 'min_score', 'threshold'])),
      maxContextChars: toNumber(
        pick(retrieval, ['maxContextChars', 'max_context_chars', 'contextChars'])
      ),
      corpusExamples: toNumber(
        pick(retrieval, ['corpusExamples', 'corpus_examples', 'fewShotExamples'])
      )
    },
    index: {
      knowledgeTotal: toNumber(pick(index, ['knowledgeTotal', 'knowledge_total'])),
      knowledgeReady: toNumber(pick(index, ['knowledgeReady', 'knowledge_ready'])),
      knowledgePending: toNumber(pick(index, ['knowledgePending', 'knowledge_pending'])),
      knowledgeFailed: toNumber(pick(index, ['knowledgeFailed', 'knowledge_failed'])),
      corpusTotal: toNumber(pick(index, ['corpusTotal', 'corpus_total'])),
      corpusReady: toNumber(pick(index, ['corpusReady', 'corpus_ready'])),
      lastReindexAt: toText(pick(index, ['lastReindexAt', 'last_reindex_at'])),
      vectorAvailable: toBoolean(pick(index, ['vectorAvailable', 'vector_available']))
    }
  }
}

/** POST /api/settings/rag/test 归一化：ok 缺失时为 null，由界面按失败展示（不臆造成功） */
export function normalizeRagTest(raw) {
  const data = asObject(raw)
  return {
    ok: toBoolean(pick(data, ['ok', 'success'])),
    providerLabel: toText(pick(data, ['providerLabel', 'provider'])),
    model: toText(pick(data, ['model'])),
    dimensions: toNumber(pick(data, ['dimensions', 'dimension'])),
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency_ms', 'latency'])),
    message: toText(pick(data, ['message', 'detail', 'error']))
  }
}

/** POST /api/settings/rag/reindex 归一化（异步执行，仅返回排队 / 跳过数量） */
export function normalizeReindexResult(raw) {
  const data = asObject(raw)
  return {
    queued: toNumber(pick(data, ['queued', 'queuedCount', 'total'])),
    skipped: toNumber(pick(data, ['skipped', 'skippedCount', 'ignored'])),
    message: toText(pick(data, ['message', 'detail']))
  }
}

/* ---------- 知识库检索预览 ---------- */

function normalizeSearchHit(raw) {
  const data = asObject(raw)
  return {
    chunkId: toNumber(pick(data, ['chunkId', 'chunk_id', 'id'])),
    entryId: toNumber(pick(data, ['entryId', 'entry_id', 'knowledgeId'])),
    title: toText(pick(data, ['title', 'name'])),
    category: toText(pick(data, ['category', 'type'])),
    content: toText(pick(data, ['content', 'text', 'body'])),
    score: toNumber(pick(data, ['score', 'similarity', 'distance']))
  }
}

/** POST /api/knowledge/search 归一化：results 缺失 / 非数组时为空列表 */
export function normalizeKnowledgeSearch(raw) {
  const data = asObject(raw)
  const results = pick(data, ['results', 'hits', 'items'])
  return {
    query: toText(pick(data, ['query', 'keyword'])),
    results: (Array.isArray(results) ? results : [])
      .filter((item) => item && typeof item === 'object')
      .map(normalizeSearchHit)
  }
}

/* ---------- RAG 效果评测 ---------- */

function normalizeMetrics(raw) {
  const data = asObject(raw)
  return {
    evaluated: toNumber(pick(data, ['evaluated', 'evaluatedCount'])),
    correct: toNumber(pick(data, ['correct', 'correctCount'])),
    failed: toNumber(pick(data, ['failed', 'failedCount', 'errorCount'])),
    accuracy: toNumber(pick(data, ['accuracy'])),
    precision: toNumber(pick(data, ['precision'])),
    recall: toNumber(pick(data, ['recall'])),
    tp: toNumber(pick(data, ['tp', 'truePositive', 'truePositives'])),
    fp: toNumber(pick(data, ['fp', 'falsePositive', 'falsePositives'])),
    tn: toNumber(pick(data, ['tn', 'trueNegative', 'trueNegatives'])),
    fn: toNumber(pick(data, ['fn', 'falseNegative', 'falseNegatives'])),
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency_ms', 'latency']))
  }
}

function normalizeCase(raw) {
  const data = asObject(raw)
  return {
    id: toNumber(pick(data, ['id', 'caseId', 'case_id', 'corpusId'])),
    label: toText(pick(data, ['label', 'expected', 'truth'])),
    baselinePrediction: toText(
      pick(data, ['baselinePrediction', 'baselineLabel', 'baseline'])
    ),
    ragPrediction: toText(pick(data, ['ragPrediction', 'ragLabel', 'rag'])),
    baselineConfidence: toNumber(pick(data, ['baselineConfidence', 'baselineScore'])),
    ragConfidence: toNumber(pick(data, ['ragConfidence', 'ragScore'])),
    refs: toStringList(pick(data, ['refs', 'references', 'citations'])),
    error: toText(pick(data, ['error', 'errorMessage', 'failure']))
  }
}

/** POST /api/settings/rag/evaluate 响应归一化 */
export function normalizeRagEvaluation(raw) {
  const data = asObject(raw)
  const delta = asObject(pick(data, ['delta', 'improvement']))
  const cases = pick(data, ['cases', 'results', 'samples'])
  return {
    model: toText(pick(data, ['model'])),
    sampleSize: toNumber(pick(data, ['sampleSize', 'sample_size', 'size'])),
    baseline: normalizeMetrics(pick(data, ['baseline', 'baselineMetrics'])),
    rag: normalizeMetrics(pick(data, ['rag', 'ragMetrics'])),
    delta: {
      accuracy: toNumber(pick(delta, ['accuracy', 'accuracyDelta'])),
      precision: toNumber(pick(delta, ['precision', 'precisionDelta'])),
      recall: toNumber(pick(delta, ['recall', 'recallDelta']))
    },
    cases: (Array.isArray(cases) ? cases : [])
      .filter((item) => item && typeof item === 'object')
      .map(normalizeCase)
  }
}

/* ---------- 展示辅助 ---------- */

/** 文件大小展示：B / KB / MB，无效值显示占位 */
export function formatFileSize(bytes) {
  const num = toNumber(bytes)
  if (num === null || num < 0) return '—'
  if (num < 1024) return `${Math.round(num)} B`
  if (num < 1024 * 1024) return `${(num / 1024).toFixed(1)} KB`
  return `${(num / 1024 / 1024).toFixed(1)} MB`
}
