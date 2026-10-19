/**
 * AI 治理（供应商注册表 / 用量成本）归一化 + 审计文案单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AUDIT_ACTION_OPTIONS, auditActionLabel } from '../src/constants.js'
import {
  formatPricePer1k,
  isProviderEnabled,
  normalizeProvider,
  normalizeProviders,
  normalizeProviderTest
} from '../src/utils/providers.js'
import { normalizeAiUsage } from '../src/utils/aiUsage.js'
import { toStringList } from '../src/utils/shape.js'

test('供应商：契约字段完整归一化', () => {
  const provider = normalizeProvider({
    key: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    chatPath: '/chat/completions',
    defaultModel: 'deepseek-flash',
    models: ['deepseek-flash', 'deepseek-v4-pro'],
    jsonMode: true,
    maxTokens: 2048,
    inputPricePer1k: 0.00014,
    outputPricePer1k: 0.00028,
    enabled: true,
    builtIn: true,
    apiKeyMasked: '****abcd',
    apiKeyConfigured: true
  })
  assert.deepEqual(provider, {
    key: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    chatPath: '/chat/completions',
    defaultModel: 'deepseek-flash',
    models: ['deepseek-flash', 'deepseek-v4-pro'],
    jsonMode: true,
    maxTokens: 2048,
    inputPricePer1k: 0.00014,
    outputPricePer1k: 0.00028,
    enabled: true,
    builtIn: true,
    apiKeyMasked: '****abcd',
    apiKeyConfigured: true
  })
})

test('供应商：兼容字段重命名 / 字符串布尔 / 逗号模型列表，默认模型自动补入', () => {
  const provider = normalizeProvider({
    id: 'my-gateway',
    name: '自建网关',
    endpoint: 'https://gw.example.com',
    path: '/v1/chat/completions',
    model: 'qwen-max',
    modelList: 'qwen-max, qwen-plus,qwen-max',
    json_mode: 'false',
    max_tokens: '1024',
    inputPrice: '0.5',
    output_price_per_1k: '1.5',
    enabled: 'true',
    built_in: 'false',
    apiKeyMasked: '',
    keyConfigured: 'false'
  })
  assert.equal(provider.key, 'my-gateway')
  assert.equal(provider.label, '自建网关')
  assert.equal(provider.baseUrl, 'https://gw.example.com')
  assert.equal(provider.chatPath, '/v1/chat/completions')
  assert.equal(provider.jsonMode, false)
  assert.equal(provider.maxTokens, 1024)
  assert.equal(provider.inputPricePer1k, 0.5)
  assert.equal(provider.outputPricePer1k, 1.5)
  assert.equal(provider.enabled, true)
  assert.equal(provider.builtIn, false)
  assert.equal(provider.apiKeyConfigured, false)
  // defaultModel 自动排到 modelList 首位且不重复
  assert.deepEqual(provider.models, ['qwen-max', 'qwen-plus'])
})

test('供应商：缺字段 / 非对象安全兜底，未知状态保持 null（不臆造启用）', () => {
  for (const raw of [null, undefined, 'oops', 42, []]) {
    const provider = normalizeProvider(raw)
    assert.equal(provider.key, '')
    assert.equal(provider.label, '')
    assert.equal(provider.enabled, null)
    assert.equal(provider.jsonMode, null)
    assert.equal(provider.builtIn, false)
    assert.deepEqual(provider.models, [])
    assert.equal(provider.apiKeyConfigured, null)
  }
})

test('供应商列表：兼容裸数组 / content / items / providers / data 包装', () => {
  const row = { key: 'a', label: 'A' }
  assert.equal(normalizeProviders([row]).length, 1)
  for (const wrapper of [
    { content: [row] },
    { items: [row] },
    { providers: [row] },
    { data: [row] }
  ]) {
    const list = normalizeProviders(wrapper)
    assert.equal(list.length, 1, JSON.stringify(wrapper))
    assert.equal(list[0].key, 'a')
  }
  // 无 key / label 的脏数据被丢弃
  assert.deepEqual(normalizeProviders([null, {}, { key: 'ok' }]).map((p) => p.key), ['ok'])
  assert.deepEqual(normalizeProviders('oops'), [])
})

test('供应商测试结果：ok / latencyMs / model / reply / message 归一化', () => {
  assert.deepEqual(
    normalizeProviderTest({
      ok: true,
      latencyMs: 321,
      model: 'deepseek-flash',
      providerLabel: 'DeepSeek',
      reply: '{"ok":true}',
      message: '连通正常'
    }),
    {
      ok: true,
      latencyMs: 321,
      model: 'deepseek-flash',
      providerLabel: 'DeepSeek',
      reply: '{"ok":true}',
      message: '连通正常'
    }
  )
  // success / latency / requestedModel / content / error 别名
  const alias = normalizeProviderTest({
    success: 'true',
    latency: '42',
    requestedModel: 'm1',
    content: 'hi',
    error: 'boom'
  })
  assert.equal(alias.ok, true)
  assert.equal(alias.latencyMs, 42)
  assert.equal(alias.model, 'm1')
  assert.equal(alias.reply, 'hi')
  assert.equal(alias.message, 'boom')
  assert.deepEqual(normalizeProviderTest(null), {
    ok: false,
    latencyMs: null,
    model: '',
    providerLabel: '',
    reply: '',
    message: ''
  })
})

test('供应商状态 / 单价展示', () => {
  assert.equal(isProviderEnabled({ enabled: true }), true)
  assert.equal(isProviderEnabled({ enabled: false }), false)
  assert.equal(isProviderEnabled({ enabled: null }), true)
  assert.equal(isProviderEnabled(null), true)

  assert.equal(formatPricePer1k(0), '$0')
  assert.equal(formatPricePer1k(null), '—')
  assert.equal(formatPricePer1k(0.00014), '$0.00014')
  assert.equal(formatPricePer1k(0.15), '$0.15')
  assert.equal(formatPricePer1k('1.23456789'), '$1.234568')
})

test('AI 用量：汇总 + 明细归一化、全零行过滤、按日升序 / 成本倒序', () => {
  const usage = normalizeAiUsage({
    requests: 12,
    inputTokens: '1500',
    outputTokens: 300,
    costUsd: '0.0123',
    byDay: [
      { date: '2026-10-02', requests: 2, inputTokens: 100, outputTokens: 20, costUsd: 0.001 },
      { date: '2026-10-01', requests: 1, inputTokens: 50, outputTokens: 10, costUsd: 0.0005 },
      { date: '2026-10-03', requests: 0, inputTokens: 0, outputTokens: 0, costUsd: 0 }
    ],
    byProvider: [
      { providerKey: 'deepseek', providerLabel: 'DeepSeek', requests: 1, inputTokens: 50, outputTokens: 10, costUsd: 0.0005 },
      { key: 'openai', label: 'OpenAI', requests: 2, inputTokens: 200, outputTokens: 40, costUsd: 0.004 }
    ],
    byModel: [
      { model: 'gpt-4o-mini', requests: 2, inputTokens: 200, outputTokens: 40, costUsd: 0.004 },
      { modelName: 'deepseek-flash', providerLabel: 'DeepSeek', requests: 1, inputTokens: 50, outputTokens: 10, costUsd: 0.0005 }
    ]
  })
  assert.equal(usage.requests, 12)
  assert.equal(usage.inputTokens, 1500)
  assert.equal(usage.outputTokens, 300)
  assert.equal(usage.costUsd, 0.0123)
  // 按日升序，全零行被过滤
  assert.deepEqual(usage.byDay.map((r) => r.date), ['2026-10-01', '2026-10-02'])
  // 按成本倒序
  assert.deepEqual(usage.byProvider.map((r) => r.key), ['openai', 'deepseek'])
  assert.deepEqual(usage.byProvider.map((r) => r.label), ['OpenAI', 'DeepSeek'])
  assert.deepEqual(usage.byModel.map((r) => r.model), ['gpt-4o-mini', 'deepseek-flash'])
  assert.equal(usage.byModel[1].providerLabel, 'DeepSeek')
})

test('AI 用量：缺字段 / 负数 / 包装对象 / object-map 明细安全兜底', () => {
  const empty = normalizeAiUsage(null)
  assert.deepEqual(empty, {
    requests: 0,
    inputTokens: 0,
    outputTokens: 0,
    costUsd: 0,
    byDay: [],
    byProvider: [],
    byModel: []
  })
  assert.deepEqual(normalizeAiUsage({ requests: -3, inputTokens: 'x' }).requests, 0)

  // 别名 + 以对象 map 形式返回的明细
  const usage = normalizeAiUsage({
    requestCount: 1,
    promptTokens: 100,
    completionTokens: 20,
    cost: 0.001,
    daily: { '2026-10-01': { requests: 1, inputTokens: 100, outputTokens: 20, costUsd: 0.001 } },
    providers: { deepseek: { label: 'DeepSeek', requests: 1, cost: 0.001 } },
    models: { 'deepseek-flash': { requests: 1, cost: 0.001 } }
  })
  assert.equal(usage.requests, 1)
  assert.equal(usage.inputTokens, 100)
  assert.equal(usage.outputTokens, 20)
  assert.equal(usage.costUsd, 0.001)
  assert.equal(usage.byDay.length, 1)
  assert.equal(usage.byDay[0].date, '2026-10-01')
  assert.equal(usage.byProvider[0].key, 'deepseek')
  assert.equal(usage.byProvider[0].label, 'DeepSeek')
  assert.equal(usage.byModel[0].model, 'deepseek-flash')
})

test('供应商审计动作：PROVIDER_* 都有中文文案并进入筛选选项', () => {
  assert.equal(auditActionLabel('PROVIDER_CREATE'), '新增 AI 供应商')
  assert.equal(auditActionLabel('PROVIDER_UPDATE'), '更新 AI 供应商')
  assert.equal(auditActionLabel('PROVIDER_DELETE'), '删除 AI 供应商')
  assert.equal(auditActionLabel('PROVIDER_TEST'), '测试 AI 供应商')
  for (const action of ['PROVIDER_CREATE', 'PROVIDER_UPDATE', 'PROVIDER_DELETE', 'PROVIDER_TEST']) {
    assert.ok(AUDIT_ACTION_OPTIONS.some((opt) => opt.value === action), action)
  }
})

test('shape.toStringList：数组 / 逗号字符串 / 对象数组去重去空', () => {
  assert.deepEqual(toStringList(['a', ' b ', 'a', '']), ['a', 'b'])
  assert.deepEqual(toStringList('a,b, a ,'), ['a', 'b'])
  assert.deepEqual(toStringList([{ value: 'v1' }, { label: 'v2' }, { model: 'v3' }, {}]), ['v1', 'v2', 'v3'])
  assert.deepEqual(toStringList(null), [])
})
