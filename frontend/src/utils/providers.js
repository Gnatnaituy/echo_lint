/**
 * AI 供应商（注册表）响应归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 3a）：
 * - GET    /api/settings/providers            -> [{key,label,baseUrl,chatPath,defaultModel,models[],jsonMode,
 *                                                 maxTokens,inputPricePer1k,outputPricePer1k,enabled,builtIn,
 *                                                 apiKeyMasked,apiKeyConfigured}]
 * - POST   /api/settings/providers            -> 自定义供应商
 * - PUT    /api/settings/providers/{key}      -> apiKey 可选（空表示不修改）
 * - DELETE /api/settings/providers/{key}      -> 仅自定义
 * - POST   /api/settings/providers/{key}/test -> {ok,latencyMs,model,reply,message}
 *
 * 归一化不臆造业务默认值：字段缺失时 enabled / jsonMode 等保持 null，
 * API key 只回掩码，前端永远不接触明文。
 */

import { asObject, pick, toBoolean, toNumber, toText, toStringList } from './shape.js'

/** 单个供应商：兼容后端字段重命名（id/label 等） */
export function normalizeProvider(raw) {
  const data = asObject(raw)
  const models = toStringList(pick(data, ['models', 'modelList', 'modelNames']))
  const defaultModel = toText(pick(data, ['defaultModel', 'model', 'default_model']))
  if (defaultModel && !models.includes(defaultModel)) models.unshift(defaultModel)
  return {
    key: toText(pick(data, ['key', 'id', 'providerKey', 'providerId'])),
    label: toText(pick(data, ['label', 'name', 'displayLabel'])),
    baseUrl: toText(pick(data, ['baseUrl', 'baseURL', 'base_url', 'endpoint'])),
    chatPath: toText(pick(data, ['chatPath', 'chat_path', 'path'])),
    defaultModel,
    models,
    jsonMode: toBoolean(pick(data, ['jsonMode', 'json_mode'])),
    maxTokens: toNumber(pick(data, ['maxTokens', 'max_tokens'])),
    inputPricePer1k: toNumber(pick(data, ['inputPricePer1k', 'input_price_per_1k', 'inputPrice'])),
    outputPricePer1k: toNumber(
      pick(data, ['outputPricePer1k', 'output_price_per_1k', 'outputPrice'])
    ),
    enabled: toBoolean(pick(data, ['enabled'])),
    builtIn: toBoolean(pick(data, ['builtIn', 'built_in'])) === true,
    apiKeyMasked: toText(pick(data, ['apiKeyMasked', 'api_key_masked', 'apiKeyMask'])),
    apiKeyConfigured: toBoolean(
      pick(data, ['apiKeyConfigured', 'api_key_configured', 'keyConfigured'])
    )
  }
}

/** 供应商列表：兼容裸数组 / {content|items|providers|data: []} 包装 */
export function normalizeProviders(raw) {
  let list = []
  if (Array.isArray(raw)) list = raw
  else {
    const data = asObject(raw)
    for (const key of ['content', 'items', 'providers', 'data']) {
      if (Array.isArray(data[key])) {
        list = data[key]
        break
      }
    }
  }
  return list.map(normalizeProvider).filter((p) => p.key || p.label)
}

/** 连通性测试结果：ok / latencyMs / model / reply / message 统一形状 */
export function normalizeProviderTest(raw) {
  const data = asObject(raw)
  return {
    ok: toBoolean(pick(data, ['ok', 'success'])) === true,
    latencyMs: toNumber(pick(data, ['latencyMs', 'latency', 'elapsedMs'])),
    model: toText(pick(data, ['model', 'requestedModel'])),
    providerLabel: toText(pick(data, ['providerLabel', 'label'])),
    reply: toText(pick(data, ['reply', 'content', 'response'])),
    message: toText(pick(data, ['message', 'error', 'detail']))
  }
}

/** 是否可直接用于调用：仅当后端明确返回 enabled=false 时视为停用（缺字段不阻断） */
export function isProviderEnabled(provider) {
  return provider?.enabled !== false
}

/** 单价展示（USD / 1K tokens）：0 显示 $0，最多 6 位小数并去掉末尾 0 */
export function formatPricePer1k(value) {
  const price = toNumber(value)
  if (price === null) return '—'
  if (price === 0) return '$0'
  const text = price.toFixed(6).replace(/0+$/, '').replace(/\.$/, '')
  return `$${text}`
}

/**
 * 复筛页「供应商网格」合并视图：注册表（GET /settings/providers）+ screen-model 视图。
 *
 * - 网格主体用注册表（含掩码 / 是否内置 / 是否启用 / 候选模型），注册表不可用时回退
 *   screen-model 自带的 providers（此时没有编辑/删除入口，避免用不完整表单覆盖配置）。
 * - 「当前生效」与「当前选中」来自 screen-model（providerId / 选择草稿）。
 * - disabled + disabledReason 只给界面灰显与提示：停用 / 未配置 Key 的供应商不可切换，
 *   但仍可点「编辑」补全密钥（内置供应商不可删）。
 *
 * @param {{providers?: Array, view?: object|null, selectedKey?: string}} input
 * @returns {Array<object>} 供网格渲染的卡片数据（顺序与注册表一致）
 */
export function mergeScreeningProviders({ providers, view, selectedKey = '' } = {}) {
  const registry = Array.isArray(providers) ? providers.filter(Boolean) : []
  const rawView = asObject(view)
  const viewProviders = Array.isArray(rawView.providers) ? rawView.providers : []
  // 注册表为空 / 不可用时回退 screen-model 的 providers：可切换但不可编辑（避免残缺表单覆盖配置）
  const fallback = registry.length === 0
  const source = fallback ? viewProviders : registry
  const activeKey = toText(rawView.providerId).toLowerCase()
  const selectedKeyText = toText(selectedKey || rawView.providerId).toLowerCase()

  return source.map((item) => {
    const data = asObject(item)
    const key = toText(pick(data, ['key', 'id', 'providerKey', 'providerId']))
    const normalizedKey = key.toLowerCase()
    const enabled = toBoolean(pick(data, ['enabled']))
    const keyConfigured = toBoolean(pick(data, ['apiKeyConfigured', 'keyConfigured']))
    const models = toStringList(pick(data, ['models', 'modelList', 'modelNames']))
    const defaultModel = toText(pick(data, ['defaultModel', 'model']))
    if (defaultModel && !models.includes(defaultModel)) models.unshift(defaultModel)
    // 停用优先于未配 Key：两者都会让卡片灰显，但原因文案不同
    const disabledReason =
      enabled === false ? 'disabled' : keyConfigured === false ? 'missingKey' : ''
    return {
      key,
      label: toText(pick(data, ['label', 'name'])),
      baseUrl: toText(pick(data, ['baseUrl', 'baseURL', 'base_url', 'endpoint'])),
      models,
      defaultModel,
      builtIn: toBoolean(pick(data, ['builtIn', 'built_in'])) === true,
      enabled,
      keyConfigured,
      // 运行时就绪状态（语音转写等场景由视图提供；复筛供应商视图无该字段时保持 null）
      ready: toBoolean(pick(data, ['ready', 'available'])),
      keyMasked: toText(pick(data, ['apiKeyMasked', 'masked'])),
      current: !!normalizedKey && normalizedKey === activeKey,
      selected: !!normalizedKey && normalizedKey === selectedKeyText,
      disabled: disabledReason !== '',
      disabledReason,
      fallback
    }
  })
}
