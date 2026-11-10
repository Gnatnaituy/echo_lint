/**
 * 语音转写（ASR）契约归一化单测。
 *
 * 契约见 docs/asr-and-settings-refactor.md §2.1 / §2.2：
 * - GET / PUT /api/settings/asr 的视图（provider 切换 + 掩码密钥 + ready 状态）
 * - POST /api/settings/asr/test 的连通性结果
 * - PUT 请求体：留空 apiKey = 保持已保存密钥；省略字段 = 不修改
 * 后端与前端并行开发，这里覆盖「缺字段稳定结构」与「候选模型兜底」，避免页面臆造数据。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ASR_DASHSCOPE_MODELS,
  ASR_DEFAULT_DASHSCOPE_BASE_URL,
  ASR_DEFAULT_DASHSCOPE_MODEL,
  ASR_DEFAULT_LANGUAGE_HINTS,
  ASR_DEFAULT_LOCAL_BASE_URL,
  ASR_DEFAULT_LOCAL_MODEL,
  ASR_PROVIDERS,
  ASR_PROVIDER_DASHSCOPE,
  ASR_PROVIDER_LOCAL,
  ASR_TIMEOUT_DEFAULT_SECONDS,
  ASR_TIMEOUT_MAX_SECONDS,
  ASR_TIMEOUT_MIN_SECONDS,
  asrApiKey,
  asrModelCandidates,
  asrProviderBlockedReason,
  asrProviderSelectable,
  buildAsrUpdatePayload,
  isAsrProvider,
  normalizeAsrProvider,
  normalizeAsrSettings,
  normalizeAsrTest,
  normalizeAsrTimeout
} from '../src/utils/asr.js'

/* ---------- 视图归一化 ---------- */

test('ASR 视图：完整契约字段归一化（§2.2 示例）', () => {
  const settings = normalizeAsrSettings({
    activeProvider: 'dashscope',
    timeoutSeconds: 600,
    local: {
      label: '本地 whisper.cpp',
      ready: true,
      baseUrl: 'http://host.docker.internal:9900',
      model: 'whisper-1',
      apiKeyConfigured: true,
      apiKeyMasked: '****ocal',
      audioPath: '/v1/audio/transcriptions'
    },
    dashscope: {
      label: '阿里云百炼',
      ready: true,
      baseUrl: 'https://dashscope.aliyuncs.com',
      model: 'qwen-audio-3.1-asr-flash-filetrans',
      models: ['qwen-audio-3.1-asr-flash-filetrans', 'qwen-audio-3.0-asr-flash-filetrans'],
      languageHints: 'zh',
      apiKeyConfigured: true,
      apiKeyMasked: '****abcd'
    }
  })
  assert.deepEqual(settings, {
    activeProvider: 'dashscope',
    timeoutSeconds: 600,
    local: {
      label: '本地 whisper.cpp',
      ready: true,
      baseUrl: 'http://host.docker.internal:9900',
      model: 'whisper-1',
      apiKeyConfigured: true,
      apiKeyMasked: '****ocal',
      audioPath: '/v1/audio/transcriptions'
    },
    dashscope: {
      label: '阿里云百炼',
      ready: true,
      baseUrl: 'https://dashscope.aliyuncs.com',
      model: 'qwen-audio-3.1-asr-flash-filetrans',
      models: ['qwen-audio-3.1-asr-flash-filetrans', 'qwen-audio-3.0-asr-flash-filetrans'],
      languageHints: 'zh',
      apiKeyConfigured: true,
      apiKeyMasked: '****abcd'
    }
  })
})

test('ASR 视图：缺字段返回稳定结构（null / 空串 / 空数组，不臆造就绪状态）', () => {
  assert.deepEqual(normalizeAsrSettings(null), {
    activeProvider: '',
    timeoutSeconds: null,
    local: {
      label: '',
      ready: null,
      baseUrl: '',
      model: '',
      apiKeyConfigured: null,
      apiKeyMasked: '',
      audioPath: ''
    },
    dashscope: {
      label: '',
      ready: null,
      baseUrl: '',
      model: '',
      models: [],
      languageHints: '',
      apiKeyConfigured: null,
      apiKeyMasked: ''
    }
  })
  // 非对象输入同样安全兜底（旧后端 / 网关错误页返回字符串）
  assert.equal(normalizeAsrSettings('oops').activeProvider, '')
  assert.deepEqual(normalizeAsrSettings([]).dashscope.models, [])
})

test('ASR 视图：宽松布尔 / 数字 / 别名兼容（大小写与兼容字段）', () => {
  const settings = normalizeAsrSettings({
    provider: 'DASHSCOPE',
    timeout: '900',
    local: {
      available: 'false',
      base_url: 'http://127.0.0.1:9900',
      defaultModel: 'whisper-1',
      keyConfigured: 'true',
      transcriptionPath: '/v1/audio/transcriptions'
    },
    bailian: {
      ready: 'true',
      base_url: 'https://example.aliyuncs.com',
      defaultModel: 'qwen-audio-3.0-asr-flash-filetrans',
      modelCandidates: [' a ', 'b', 'a', ''],
      language_hints: ' zh,en ',
      keyMasked: '****9f2c'
    }
  })
  assert.equal(settings.activeProvider, 'dashscope')
  assert.equal(settings.timeoutSeconds, 900)
  assert.equal(settings.local.ready, false)
  assert.equal(settings.local.baseUrl, 'http://127.0.0.1:9900')
  assert.equal(settings.local.model, 'whisper-1')
  assert.equal(settings.local.apiKeyConfigured, true)
  assert.equal(settings.local.audioPath, '/v1/audio/transcriptions')
  assert.equal(settings.dashscope.ready, true)
  assert.equal(settings.dashscope.baseUrl, 'https://example.aliyuncs.com')
  assert.equal(settings.dashscope.model, 'qwen-audio-3.0-asr-flash-filetrans')
  assert.deepEqual(settings.dashscope.models, ['a', 'b'])
  assert.equal(settings.dashscope.languageHints, 'zh,en')
  assert.equal(settings.dashscope.apiKeyMasked, '****9f2c')
})

test('ASR 视图：不再携带 apiKeySource / envHints（密钥只存数据库，接口只回掩码）', () => {
  const settings = normalizeAsrSettings({
    activeProvider: 'local',
    local: {
      ready: true,
      apiKeyConfigured: true,
      apiKeyMasked: '****ocal',
      // 旧后端 / 兼容字段：应被忽略，不进入归一化结构
      apiKeySource: 'env',
      envHints: { baseUrl: 'WHISPER_BASE_URL', apiKey: 'WHISPER_API_KEY' }
    },
    dashscope: { ready: false, apiKeyConfigured: false }
  })
  assert.ok(!('apiKeySource' in settings.local))
  assert.ok(!('envHints' in settings.local))
  assert.ok(!('apiKeySource' in settings.dashscope))
  assert.ok(!('envHints' in settings.dashscope))
  assert.equal(settings.local.apiKeyMasked, '****ocal')
  assert.equal(settings.dashscope.apiKeyMasked, '')
})

test('ASR 视图：非法 / 未知 provider 不伪装成 local', () => {
  assert.equal(normalizeAsrProvider('LOCAL'), 'local')
  assert.equal(normalizeAsrProvider(' DashScope '), 'dashscope')
  assert.equal(normalizeAsrProvider('openai'), '')
  assert.equal(normalizeAsrProvider(undefined), '')
  assert.deepEqual(ASR_PROVIDERS, [ASR_PROVIDER_LOCAL, ASR_PROVIDER_DASHSCOPE])
  assert.equal(isAsrProvider('local'), true)
  assert.equal(isAsrProvider('whisper'), false)
  assert.equal(normalizeAsrSettings({ provider: 'openai' }).activeProvider, '')
})

test('ASR 超时：收敛到契约 30~1800 秒，非数字返回 null', () => {
  assert.equal(normalizeAsrTimeout(600), 600)
  assert.equal(normalizeAsrTimeout('900'), 900)
  assert.equal(normalizeAsrTimeout(10), ASR_TIMEOUT_MIN_SECONDS)
  assert.equal(normalizeAsrTimeout(99999), ASR_TIMEOUT_MAX_SECONDS)
  assert.equal(normalizeAsrTimeout(600.4), 600)
  assert.equal(normalizeAsrTimeout('abc'), null)
  assert.equal(normalizeAsrTimeout(null), null)
})

/* ---------- 连通性测试 ---------- */

test('ASR 测试结果：完整字段归一化', () => {
  assert.deepEqual(
    normalizeAsrTest({
      ok: true,
      provider: 'dashscope',
      model: 'qwen-audio-3.1-asr-flash-filetrans',
      latencyMs: 180,
      message: '连通正常（上传凭证获取成功）'
    }),
    {
      ok: true,
      provider: 'dashscope',
      model: 'qwen-audio-3.1-asr-flash-filetrans',
      latencyMs: 180,
      message: '连通正常（上传凭证获取成功）'
    }
  )
})

test('ASR 测试结果：ok 缺失 / 非 true 一律按失败，不臆造成功', () => {
  const result = normalizeAsrTest({ provider: 'local' })
  assert.equal(result.ok, false)
  assert.equal(result.provider, 'local')
  assert.equal(result.model, '')
  assert.equal(result.latencyMs, null)
  assert.equal(result.message, '')
  assert.equal(normalizeAsrTest({ ok: 'false' }).ok, false)
  assert.equal(normalizeAsrTest({ success: true, latency: '42' }).ok, true)
  assert.equal(normalizeAsrTest({ success: true, latency: '42' }).latencyMs, 42)
  assert.deepEqual(normalizeAsrTest(null), {
    ok: false,
    provider: '',
    model: '',
    latencyMs: null,
    message: ''
  })
})

/* ---------- 候选模型 ---------- */

test('候选模型：后端返回优先，去空白 / 去重', () => {
  assert.deepEqual(asrModelCandidates([' a ', 'b', 'a', '']), ['a', 'b'])
})

test('候选模型：当前选中模型不在候选里时补到最前（下拉回显）', () => {
  assert.deepEqual(asrModelCandidates(['a', 'b'], 'c'), ['c', 'a', 'b'])
  assert.deepEqual(asrModelCandidates(['a', 'b'], 'a'), ['a', 'b'])
})

test('候选模型：后端未返回时回退文档候选（仍允许手填）', () => {
  assert.deepEqual(asrModelCandidates([]), ASR_DASHSCOPE_MODELS)
  assert.deepEqual(asrModelCandidates(null), ASR_DASHSCOPE_MODELS)
  assert.deepEqual(asrModelCandidates(undefined, ''), ASR_DASHSCOPE_MODELS)
  // 当前模型为空时不额外补空串
  assert.deepEqual(asrModelCandidates(null, ''), ASR_DASHSCOPE_MODELS)
})

/* ---------- 留空密钥语义 ---------- */

test('密钥提交：留空 / 全空白 = 保持原值（返回 undefined，不写回掩码）', () => {
  assert.equal(asrApiKey(''), undefined)
  assert.equal(asrApiKey('   '), undefined)
  assert.equal(asrApiKey(null), undefined)
  assert.equal(asrApiKey(undefined), undefined)
  assert.equal(asrApiKey('  sk-abc123  '), 'sk-abc123')
})

test('PUT 请求体：只提交提供过的字段，留空密钥不会出现在 JSON 里', () => {
  // 仅切换 provider：不携带任何配置，避免覆盖已保存的本地 / 百炼配置
  assert.deepEqual(buildAsrUpdatePayload({ activeProvider: 'DASHSCOPE' }), {
    activeProvider: 'dashscope'
  })

  // 本地配置：空白 apiKey 被省略；允许显式提交空地址（恢复 env 兜底）
  const local = buildAsrUpdatePayload({
    local: { baseUrl: ' http://127.0.0.1:9900 ', model: '', apiKey: '   ' }
  })
  assert.deepEqual(local, { local: { baseUrl: 'http://127.0.0.1:9900', model: '' } })
  assert.ok(!JSON.stringify(local).includes('apiKey'))

  // 非空密钥去空白后提交
  assert.deepEqual(buildAsrUpdatePayload({ local: { apiKey: ' sk-local ' } }), {
    local: { apiKey: 'sk-local' }
  })
})

test('PUT 请求体：超时收敛、空 payload 不产生嵌套空对象', () => {
  assert.deepEqual(buildAsrUpdatePayload({ timeoutSeconds: 900 }), { timeoutSeconds: 900 })
  assert.deepEqual(buildAsrUpdatePayload({ timeoutSeconds: 5 }), {
    timeoutSeconds: ASR_TIMEOUT_MIN_SECONDS
  })
  assert.deepEqual(buildAsrUpdatePayload({ timeoutSeconds: 'abc' }), {})
  assert.deepEqual(buildAsrUpdatePayload({ activeProvider: 'openai' }), {})
  assert.deepEqual(buildAsrUpdatePayload({ local: {}, dashscope: {} }), {})
  assert.deepEqual(buildAsrUpdatePayload(), {})
  assert.deepEqual(buildAsrUpdatePayload({ local: 'oops', dashscope: [] }), {})
})

test('PUT 请求体：百炼配置字段完整（含语言提示与超时）', () => {
  assert.deepEqual(
    buildAsrUpdatePayload({
      timeoutSeconds: 600,
      dashscope: {
        baseUrl: 'https://dashscope.aliyuncs.com',
        model: 'qwen-audio-3.1-asr-flash-filetrans',
        languageHints: 'zh',
        apiKey: 'sk-xyz'
      }
    }),
    {
      timeoutSeconds: 600,
      dashscope: {
        baseUrl: 'https://dashscope.aliyuncs.com',
        model: 'qwen-audio-3.1-asr-flash-filetrans',
        languageHints: 'zh',
        apiKey: 'sk-xyz'
      }
    }
  )
})

test('PUT 请求体：切换 provider 可同时携带未保存的配置（一次提交，减少两步操作）', () => {
  const payload = buildAsrUpdatePayload({
    activeProvider: 'local',
    timeoutSeconds: 300,
    local: { baseUrl: 'http://127.0.0.1:9900', model: 'whisper-1', apiKey: '' },
    dashscope: { baseUrl: '', model: '', languageHints: '', apiKey: ' sk-keep ' }
  })
  assert.deepEqual(payload, {
    activeProvider: 'local',
    timeoutSeconds: 300,
    // apiKey 留空 → 省略（保持原值）；空地址/模型/语言提示显式提交以恢复 env 兜底
    local: { baseUrl: 'http://127.0.0.1:9900', model: 'whisper-1' },
    dashscope: { baseUrl: '', model: '', languageHints: '', apiKey: 'sk-keep' }
  })
  // 仅保存配置（不带 activeProvider）时请求体里没有切换字段，后端不会切换生效服务
  assert.ok(!('activeProvider' in buildAsrUpdatePayload({ local: { apiKey: 'sk-x' } })))
})

/* ---------- 可切换性 ---------- */

test('可切换性：只有 ready=false 才禁用；缺失 ready 时交给后端校验', () => {
  const view = normalizeAsrSettings({
    activeProvider: 'local',
    local: { ready: true, apiKeyConfigured: true },
    dashscope: { ready: false, apiKeyConfigured: false }
  })
  assert.equal(asrProviderSelectable(view, 'local'), true)
  assert.equal(asrProviderSelectable(view, 'dashscope'), false)
  assert.equal(asrProviderBlockedReason(view, 'dashscope'), 'missingKey')
  assert.equal(asrProviderBlockedReason(view, 'local'), '')

  const dashNotReady = normalizeAsrSettings({
    dashscope: { ready: false, apiKeyConfigured: true }
  })
  assert.equal(asrProviderBlockedReason(dashNotReady, 'dashscope'), 'notReady')

  // 旧后端 / 字段缺失：不禁用，切换时由后端返回可读错误
  const unknown = normalizeAsrSettings({})
  assert.equal(asrProviderSelectable(unknown, 'local'), true)
  assert.equal(asrProviderSelectable(unknown, 'dashscope'), true)
  assert.equal(asrProviderBlockedReason(unknown, 'dashscope'), '')
  assert.equal(asrProviderSelectable(null, 'local'), true)
})

/* ---------- 契约默认值（仅供 UI 占位，不覆盖后端视图） ---------- */

test('契约默认值：与 §2.1 / §1 文档一致', () => {
  assert.equal(ASR_DEFAULT_LOCAL_BASE_URL, 'http://127.0.0.1:9900')
  assert.equal(ASR_DEFAULT_LOCAL_MODEL, 'whisper-1')
  assert.equal(ASR_DEFAULT_DASHSCOPE_BASE_URL, 'https://dashscope.aliyuncs.com')
  assert.equal(ASR_DEFAULT_DASHSCOPE_MODEL, 'qwen-audio-3.1-asr-flash-filetrans')
  assert.equal(ASR_DEFAULT_LANGUAGE_HINTS, 'zh')
  assert.equal(ASR_TIMEOUT_DEFAULT_SECONDS, 600)
  assert.deepEqual(ASR_DASHSCOPE_MODELS, [
    'qwen-audio-3.1-asr-flash-filetrans',
    'qwen-audio-3.0-asr-flash-filetrans'
  ])
})

/* ---------- API 层接线（axios adapter 注入，零网络） ---------- */

test('API 层：GET / PUT / POST 使用归一化函数与 silent GET', async () => {
  const { api, http } = await import('../src/api/index.js')
  const originalAdapter = http.defaults.adapter
  const calls = []
  http.defaults.adapter = async (config) => {
    calls.push(config)
    const url = String(config.url || '')
    let data = {}
    if (url === '/settings/asr' && config.method === 'get') {
      data = {
        activeProvider: 'dashscope',
        timeoutSeconds: 600,
        local: { ready: true, baseUrl: 'http://127.0.0.1:9900', model: 'whisper-1' },
        dashscope: { ready: true, model: 'qwen-audio-3.1-asr-flash-filetrans', models: [] }
      }
    } else if (url === '/settings/asr' && config.method === 'put') {
      data = { activeProvider: 'local', local: { ready: true }, dashscope: { ready: false } }
    } else if (url === '/settings/asr/test') {
      data = {
        ok: true,
        provider: 'dashscope',
        model: 'qwen-audio-3.1-asr-flash-filetrans',
        latencyMs: 180,
        message: 'ok'
      }
    }
    return { data, status: 200, statusText: '', headers: {}, config, request: {} }
  }
  try {
    const settings = await api.getAsrSettings()
    assert.equal(calls[0].url, '/settings/asr')
    assert.equal(calls[0].method, 'get')
    // GET 为 silent：403/404 由页面展示降级提示，不弹全局错误
    assert.equal(calls[0].silent, true)
    assert.equal(settings.activeProvider, 'dashscope')
    assert.equal(settings.dashscope.ready, true)

    const updated = await api.updateAsrSettings(buildAsrUpdatePayload({ activeProvider: 'local' }))
    assert.equal(calls[1].url, '/settings/asr')
    assert.equal(calls[1].method, 'put')
    assert.deepEqual(JSON.parse(calls[1].data), { activeProvider: 'local' })
    assert.equal(updated.activeProvider, 'local')

    const result = await api.testAsr('dashscope')
    assert.equal(calls[2].url, '/settings/asr/test')
    assert.equal(calls[2].method, 'post')
    assert.deepEqual(JSON.parse(calls[2].data), { provider: 'dashscope' })
    assert.equal(result.ok, true)
    assert.equal(result.latencyMs, 180)
  } finally {
    http.defaults.adapter = originalAdapter
  }
})
