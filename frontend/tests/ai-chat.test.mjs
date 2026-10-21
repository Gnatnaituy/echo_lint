/**
 * AI 对话记录：列表/详情/筛选候选归一化、消息解析与展示元数据。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  aiStageMeta,
  aiStatusMeta,
  formatChars,
  formatCostUsd,
  formatLatency,
  formatTokens,
  normalizeAiInvocationDetail,
  normalizeAiInvocationFacets,
  normalizeAiInvocationPage,
  normalizeAiInvocationSummary,
  parseAiMessages,
  prettyJson,
  roleLabel
} from '../src/utils/aiChat.js'

const SUMMARY = {
  id: 12,
  recordingId: 7,
  stage: 'SCREEN',
  providerId: 'deepseek',
  providerLabel: 'DeepSeek',
  model: 'deepseek-flash',
  endpoint: 'https://api.deepseek.com/chat/completions',
  success: true,
  degraded: false,
  latencyMs: 812,
  requestChars: 4096,
  responseChars: 160,
  inputTokens: 1200,
  outputTokens: 96,
  costMicros: 1250,
  errorMessage: null,
  createdAt: '2026-10-21T10:00:00'
}

test('列表项：契约字段完整归一化', () => {
  const row = normalizeAiInvocationSummary(SUMMARY)
  assert.equal(row.id, 12)
  assert.equal(row.recordingId, 7)
  assert.equal(row.stage, 'SCREEN')
  assert.equal(row.providerLabel, 'DeepSeek')
  assert.equal(row.inputTokens, 1200)
  assert.equal(row.costMicros, 1250)
  assert.equal(row.success, true)
})

test('列表项：snake_case / 字符串数字 / 缺字段安全兜底', () => {
  const row = normalizeAiInvocationSummary({
    id: '9',
    recording_id: '3',
    stage: 'EMBED_QUERY',
    provider_id: 'aliyun',
    latency_ms: '45',
    input_tokens: '23',
    output_tokens: '0',
    cost_micros: '5',
    success: 'true',
    error_message: 'oops'
  })
  assert.equal(row.id, 9)
  assert.equal(row.recordingId, 3)
  assert.equal(row.providerId, 'aliyun')
  assert.equal(row.latencyMs, 45)
  assert.equal(row.outputTokens, 0)
  assert.equal(row.success, true)
  assert.equal(row.errorMessage, 'oops')
  assert.equal(row.model, '')
})

test('列表页：Spring Page 与裸数组', () => {
  const page = normalizeAiInvocationPage({
    content: [SUMMARY],
    totalElements: 1,
    number: 0,
    size: 15,
    totalPages: 1
  })
  assert.equal(page.items.length, 1)
  assert.equal(page.total, 1)
  assert.equal(page.size, 15)

  const array = normalizeAiInvocationPage([SUMMARY])
  assert.equal(array.items.length, 1)
  assert.equal(array.total, 1)
})

test('详情：摘要 + 完整 request/response', () => {
  const detail = normalizeAiInvocationDetail({
    ...SUMMARY,
    request_json: '{"messages":[{"role":"system","content":"规则"}]}',
    response_json: '{"violation":false}'
  })
  assert.equal(detail.id, 12)
  assert.ok(detail.requestJson.includes('messages'))
  assert.equal(detail.responseJson, '{"violation":false}')
})

test('筛选候选：stages/providers/models 归一化与过滤', () => {
  const facets = normalizeAiInvocationFacets({
    stages: ['SCREEN', 'MINING'],
    providers: [
      { id: 'aliyun', label: '阿里云百炼' },
      { id: '', label: '无 id' }
    ],
    models: ['qwen3.7-text-embedding', '']
  })
  assert.deepEqual(facets.stages, ['SCREEN', 'MINING'])
  assert.deepEqual(facets.providers, [{ id: 'aliyun', label: '阿里云百炼' }])
  assert.deepEqual(facets.models, ['qwen3.7-text-embedding'])
})

test('消息解析：system/user 顺序与非法 JSON', () => {
  const messages = parseAiMessages(
    JSON.stringify({
      messages: [
        { role: 'system', content: '你是审查助手' },
        { role: 'user', content: '待审文本' }
      ]
    })
  )
  assert.deepEqual(messages, [
    { role: 'system', content: '你是审查助手' },
    { role: 'user', content: '待审文本' }
  ])
  assert.deepEqual(parseAiMessages('not json'), [])
  assert.deepEqual(parseAiMessages('{"input":["x"]}'), [])
})

test('JSON 格式化：合法 JSON 缩进，非法原样返回', () => {
  assert.equal(prettyJson('{"a":1}'), '{\n  "a": 1\n}')
  assert.equal(prettyJson('plain'), 'plain')
  assert.equal(prettyJson(null), '')
})

test('阶段 / 状态 / 角色元数据', () => {
  assert.equal(aiStageMeta('SCREEN').label, '语义复筛')
  assert.equal(aiStageMeta('EMBED_QUERY').label, '检索向量化')
  assert.equal(aiStageMeta('CUSTOM').label, 'CUSTOM')
  assert.equal(aiStatusMeta({ success: true, degraded: false }).label, '成功')
  assert.equal(aiStatusMeta({ success: true, degraded: true }).label, '降级')
  assert.equal(aiStatusMeta({ success: false }).label, '失败')
  assert.equal(roleLabel('system'), '系统提示词')
  assert.equal(roleLabel('user'), '用户提示词')
  assert.equal(roleLabel('assistant'), '模型回复')
})

test('展示格式：成本 / tokens / 耗时 / 字符数', () => {
  assert.equal(formatCostUsd(1_250_000), '$1.250000')
  assert.equal(formatCostUsd(null), '—')
  assert.equal(formatTokens({ inputTokens: 1200, outputTokens: 96 }), '1200 / 96')
  assert.equal(formatTokens({}), '—')
  assert.equal(formatLatency(812), '812 ms')
  assert.equal(formatChars(4096), '4.1k')
  assert.equal(formatChars(160), '160')
})
