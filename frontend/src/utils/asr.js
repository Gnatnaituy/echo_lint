/**
 * 语音转写（ASR）设置 / 连通性测试归一化。
 *
 * 后端契约（docs/asr-and-settings-refactor.md §6.2）：
 * - GET  /api/settings/asr -> {activeProvider, timeoutSeconds, local:{...}, dashscope:{...}}
 *   视图只回掩码（apiKeyMasked），不含 apiKeySource / envHints（密钥只存数据库）
 * - PUT  /api/settings/asr（apiKey 为空字符串 = 保持原值）-> 同 GET
 * - POST /api/settings/asr/test {provider?} -> {ok, provider, model, latencyMs, message}
 *
 * 后端与前端并行开发，字段可能增补 / 暂缺：缺失字段统一返回 null / '' / []，
 * 由界面显示「未配置 / 未知」，不臆造就绪状态与业务默认值。
 * 默认值常量只用于输入框 placeholder（非密钥类的 env 默认值仍由后端兜底）。
 */

import { asObject, pick, toBoolean, toNumber, toText, toStringList } from './shape.js'

/** provider 取值（对应后端 AsrProviderType.value）：本地 whisper.cpp / 阿里云百炼 */
export const ASR_PROVIDER_LOCAL = 'local'
export const ASR_PROVIDER_DASHSCOPE = 'dashscope'
export const ASR_PROVIDERS = [ASR_PROVIDER_LOCAL, ASR_PROVIDER_DASHSCOPE]

/* 契约默认值（§2.1 的 env / 代码默认）：仅供 UI 占位与说明，不伪造后端未返回的值 */
export const ASR_DEFAULT_LOCAL_BASE_URL = 'http://127.0.0.1:9900'
export const ASR_DEFAULT_LOCAL_MODEL = 'whisper-1'
export const ASR_DEFAULT_DASHSCOPE_BASE_URL = 'https://dashscope.aliyuncs.com'
export const ASR_DEFAULT_DASHSCOPE_MODEL = 'qwen-audio-3.1-asr-flash-filetrans'
export const ASR_DEFAULT_LANGUAGE_HINTS = 'zh'
export const ASR_TIMEOUT_DEFAULT_SECONDS = 600
export const ASR_TIMEOUT_MIN_SECONDS = 30
export const ASR_TIMEOUT_MAX_SECONDS = 1800

/** §1 文档给出的百炼模型候选：后端未返回 models 时兜底下拉（仍允许手填） */
export const ASR_DASHSCOPE_MODELS = [
  'qwen-audio-3.1-asr-flash-filetrans',
  'qwen-audio-3.0-asr-flash-filetrans'
]

/**
 * 百炼服务地址归一化：API 根固定是域名根，容忍用户粘贴文档里的完整地址
 * （带 /api/v1 甚至具体接口路径），统一收敛为 scheme://host，避免拼成 /api/v1/api/v1/... 返回 404。
 * 本地 whisper.cpp 地址不处理（允许带自定义路径前缀）；后端保存时同样会归一化。
 */
export function normalizeAsrBaseUrl(value) {
  const text = toText(value).trim()
  if (!text) return text
  try {
    const url = new URL(text)
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      return `${url.protocol}//${url.host}`
    }
  } catch {
    // 非标准 URL（如手滑缺 scheme）：退化为字符串规整
  }
  return text.replace(/\/+$/, '').replace(/\/api\/v1$/i, '')
}

/**
 * provider 宽松解析：大小写不敏感（PUT 允许 "DASHSCOPE"）。
 * 未知 / 空值返回 ''，由调用方决定回退（不把非法值当成 local）。
 */
export function normalizeAsrProvider(value) {
  const text = toText(value).toLowerCase()
  return ASR_PROVIDERS.includes(text) ? text : ''
}

export function isAsrProvider(value) {
  return normalizeAsrProvider(value) !== ''
}

/**
 * 转写超时（秒）：非数字返回 null；越界按契约 30~1800 收敛（后端同样会校验）。
 * 前端 input-number 与这里保持同一组边界，避免两边显示不一致。
 */
export function normalizeAsrTimeout(value) {
  const seconds = toNumber(value)
  if (seconds === null) return null
  return Math.min(ASR_TIMEOUT_MAX_SECONDS, Math.max(ASR_TIMEOUT_MIN_SECONDS, Math.round(seconds)))
}

/** 本地 whisper.cpp 区块（§2.2 local） */
export function normalizeAsrLocal(raw) {
  const data = asObject(raw)
  return {
    label: toText(pick(data, ['label', 'name'])),
    ready: toBoolean(pick(data, ['ready', 'available'])),
    baseUrl: toText(pick(data, ['baseUrl', 'baseURL', 'base_url', 'endpoint'])),
    model: toText(pick(data, ['model', 'defaultModel'])),
    apiKeyConfigured: toBoolean(pick(data, ['apiKeyConfigured', 'keyConfigured'])),
    apiKeyMasked: toText(pick(data, ['apiKeyMasked', 'keyMasked', 'masked'])),
    audioPath: toText(pick(data, ['audioPath', 'transcriptionPath']))
  }
}

/** 阿里云百炼区块（§2.2 dashscope）：多出候选模型与语言提示 */
export function normalizeAsrDashscope(raw) {
  const data = asObject(raw)
  return {
    label: toText(pick(data, ['label', 'name'])),
    ready: toBoolean(pick(data, ['ready', 'available'])),
    baseUrl: toText(pick(data, ['baseUrl', 'baseURL', 'base_url', 'endpoint'])),
    model: toText(pick(data, ['model', 'defaultModel'])),
    models: toStringList(pick(data, ['models', 'modelCandidates', 'candidates'])),
    languageHints: toText(pick(data, ['languageHints', 'language_hints', 'languages'])),
    apiKeyConfigured: toBoolean(pick(data, ['apiKeyConfigured', 'keyConfigured'])),
    apiKeyMasked: toText(pick(data, ['apiKeyMasked', 'keyMasked', 'masked']))
  }
}

/** GET / PUT /api/settings/asr 响应归一化 */
export function normalizeAsrSettings(raw) {
  const data = asObject(raw)
  return {
    activeProvider: normalizeAsrProvider(pick(data, ['activeProvider', 'provider', 'active'])),
    timeoutSeconds: normalizeAsrTimeout(pick(data, ['timeoutSeconds', 'timeout'])),
    local: normalizeAsrLocal(pick(data, ['local', 'localWhisper'])),
    dashscope: normalizeAsrDashscope(pick(data, ['dashscope', 'dashScope', 'bailian']))
  }
}

/** POST /api/settings/asr/test 归一化：ok 缺失时为 false，由界面按失败展示（不臆造成功） */
export function normalizeAsrTest(raw) {
  const data = asObject(raw)
  return {
    ok: toBoolean(pick(data, ['ok', 'success'])) === true,
    provider: normalizeAsrProvider(pick(data, ['provider'])),
    model: toText(pick(data, ['model'])),
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency'])),
    message: toText(pick(data, ['message', 'detail']))
  }
}

/**
 * 转写模型候选：去空白 / 去重；当前选中模型不在候选里时补到最前（下拉回显）；
 * 后端未返回候选时回退文档给出的两个百炼模型（仍允许手填新模型）。
 */
export function asrModelCandidates(models, currentModel = '') {
  const list = toStringList(models)
  const current = toText(currentModel)
  if (current && !list.includes(current)) list.unshift(current)
  if (list.length) return list
  return [...ASR_DASHSCOPE_MODELS]
}

/**
 * 密钥提交语义：留空 / 全空白 = 保持已保存的密钥（返回 undefined，序列化时省略该字段；
 * 后端同样把空字符串视为「保持原值」，两种写法语义一致）。非空返回去空白后的明文。
 */
export function asrApiKey(value) {
  const text = toText(value)
  return text ? text : undefined
}

const LOCAL_TEXT_FIELDS = ['baseUrl', 'model']
const DASHSCOPE_TEXT_FIELDS = ['baseUrl', 'model', 'languageHints']

/** 单个 provider 的配置片段：只提交调用方给出的文本字段；密钥留空则省略 */
function asrConfigPayload(source, fields) {
  const data = asObject(source)
  const out = {}
  for (const field of fields) {
    if (data[field] === undefined || data[field] === null) continue
    out[field] = toText(data[field])
  }
  const apiKey = asrApiKey(data.apiKey)
  if (apiKey) out.apiKey = apiKey
  return out
}

/**
 * PUT /api/settings/asr 请求体：只提交本次要改动的部分（省略 = 不修改，§2.2）。
 * - activeProvider：归一化为 local / dashscope；非法值直接省略（不会误切换）
 * - timeoutSeconds：收敛到 30~1800
 * - local / dashscope：文本字段按给定值提交（允许空串 = 恢复 env 兜底），apiKey 留空省略
 */
export function buildAsrUpdatePayload({ activeProvider, timeoutSeconds, local, dashscope } = {}) {
  const payload = {}
  if (activeProvider !== undefined && activeProvider !== null && activeProvider !== '') {
    const provider = normalizeAsrProvider(activeProvider)
    if (provider) payload.activeProvider = provider
  }
  if (timeoutSeconds !== undefined && timeoutSeconds !== null && timeoutSeconds !== '') {
    const timeout = normalizeAsrTimeout(timeoutSeconds)
    if (timeout !== null) payload.timeoutSeconds = timeout
  }
  if (local && typeof local === 'object' && !Array.isArray(local)) {
    const localPayload = asrConfigPayload(local, LOCAL_TEXT_FIELDS)
    if (Object.keys(localPayload).length) payload.local = localPayload
  }
  if (dashscope && typeof dashscope === 'object' && !Array.isArray(dashscope)) {
    const dashPayload = asrConfigPayload(dashscope, DASHSCOPE_TEXT_FIELDS)
    if (dashPayload.baseUrl !== undefined) {
      dashPayload.baseUrl = normalizeAsrBaseUrl(dashPayload.baseUrl)
    }
    if (Object.keys(dashPayload).length) payload.dashscope = dashPayload
  }
  return payload
}

/**
 * 某 provider 是否可切换：只有后端明确返回 ready=false 时才禁用；
 * ready 缺失（旧后端）时保持可选，由后端在切换时校验并返回可读错误。
 */
export function asrProviderSelectable(view, provider) {
  const block = view?.[normalizeAsrProvider(provider) || '']
  return !block || block.ready !== false
}

/** 不可切换的原因：优先区分「缺 API Key」与「服务未就绪」，供 SegmentedControl 提示 */
export function asrProviderBlockedReason(view, provider) {
  const key = normalizeAsrProvider(provider)
  const block = view?.[key]
  if (!block || block.ready !== false) return ''
  return block.apiKeyConfigured === false ? 'missingKey' : 'notReady'
}
