import axios from 'axios'
import { ElMessage } from 'element-plus'
import { t } from '../i18n/index.js'
import { blobErrorMessage, dateStamp, filenameFromDisposition } from '../utils/download.js'
import { normalizeNotificationSettings, normalizeNotificationTest } from '../utils/notifications.js'
import { normalizeProviderTest, normalizeProviders } from '../utils/providers.js'
import { normalizeAiUsage } from '../utils/aiUsage.js'
import {
  normalizeAiInvocationDetail,
  normalizeAiInvocationFacets,
  normalizeAiInvocationPage
} from '../utils/aiChat.js'
import { normalizeRetryResult, normalizeTaskPage } from '../utils/tasks.js'
import { normalizeEvaluation, normalizeScreenModel } from '../utils/evaluation.js'
import { normalizeApiKey, normalizeApiKeyList } from '../utils/apiKeys.js'
import { normalizeKnowledgeEntry, normalizeKnowledgePage } from '../utils/knowledge.js'
import { normalizeKnowledgeChunks } from '../utils/knowledgeIndex.js'
import {
  normalizeKnowledgeSearch,
  normalizeRagEvaluation,
  normalizeRagSettings,
  normalizeRagTest,
  normalizeReindexResult
} from '../utils/rag.js'

// 相对导入显式带 .js：Vite 可省略扩展名，但 node:test 直接 import 本模块时 Node ESM 要求完整路径
const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  // 会话认证：JSESSIONID 为 HttpOnly Cookie，由浏览器自动携带（前端不存 token）
  withCredentials: true
})

// 导出 axios 实例：单测可用自定义 adapter 注入响应，零依赖验证拦截器接线（P3-6）
export { http }

/** 统一兜底错误文案：跟随当前语言 */
function genericError() {
  return t('api.genericError')
}

/** 从普通 JSON 错误体里取 message（blob 错误体需异步解析，见 pickMessage） */
function payloadMessage(data) {
  if (!data) return ''
  if (typeof data === 'string') return data
  return data.message || data.error || ''
}

/**
 * 统一错误文案。
 * 下载接口（responseType:'blob'）失败时错误体会被包成 Blob，必须异步读取才能拿到 message，
 * 否则会退化成「[object Blob]」或丢失后端提示。
 * 导出供单测直接验证 blob 错误体解析（P3-6）。
 */
export async function pickMessage(error) {
  const data = error?.response?.data
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const text = (await data.text()).trim()
      if (text) {
        try {
          const parsed = JSON.parse(text)
          return payloadMessage(parsed) || error.message || genericError()
        } catch {
          // 非 JSON（如 nginx 的 HTML 错误页）：不直接回显，避免污染界面
        }
      }
    } catch {
      // 读取 Blob 失败：退回 axios 自身 message
    }
    return error.message || genericError()
  }
  return payloadMessage(data) || error.message || genericError()
}

/**
 * 全局错误提示：无 DOM 环境（node:test / SSR）下静默跳过，避免提示层把请求流程打断。
 * 浏览器里等价于 ElMessage.error。
 */
function notifyError(message) {
  if (!message || typeof document === 'undefined') return
  try {
    ElMessage.error(message)
  } catch {
    // 提示层异常不应影响错误向上抛出
  }
}

/**
 * 401 处理依赖：默认动态加载 auth / router（避免 api ←→ router/auth 的循环依赖）。
 * 单测可替换 `load`（如注入内存版 auth/router），生产行为不变。
 */
export const unauthorizedDeps = {
  async load() {
    const [{ default: auth }, { default: router }] = await Promise.all([
      import('../auth'),
      import('../router')
    ])
    return { auth, router }
  }
}

/**
 * 401 统一处理：清理本地登录态并跳转登录页（携带原始地址，登录后回跳）。
 * @param {object} error axios 错误
 * @param {{load: () => Promise<{auth: object, router: object}>}} [deps] 依赖注入（单测用）
 */
export async function handleUnauthorized(error, deps = unauthorizedDeps) {
  const { auth, router } = await deps.load()
  auth.clear()
  const current = router.currentRoute?.value || {}
  if (current.path !== '/login') {
    const full = current.fullPath || ''
    const redirect = full && full !== '/' && !full.startsWith('/login') ? full : undefined
    await router.push({ path: '/login', query: redirect ? { redirect } : {} })
  }
  return Promise.reject(new Error(await pickMessage(error)))
}

http.interceptors.response.use(
  // rawResponse：下载类请求需要拿到完整响应（读取 Content-Disposition 文件名），其余直接返回 data
  (resp) => (resp.config?.rawResponse ? resp : resp.data),
  async (error) => {
    const status = error.response?.status
    const url = String(error.config?.url || '')
    // silent：调用方自行展示错误（如系统设置里的可选接口），不弹全局提示
    const silent = error.config?.silent === true

    if (status === 401) {
      // 登录失败 / 会话探测失败交给调用方处理：不弹全局提示、不重复跳转
      if (url.includes('/auth/login') || url.includes('/auth/me')) {
        return Promise.reject(new Error(await pickMessage(error)))
      }
      return handleUnauthorized(error)
    }

    if (status === 403) {
      if (!silent) notifyError(t('api.forbidden'))
      const denied = new Error(t('api.forbidden'))
      denied.status = 403
      return Promise.reject(denied)
    }

    const msg = await pickMessage(error)
    if (!silent) notifyError(msg)
    const failure = new Error(msg)
    if (status) failure.status = status
    return Promise.reject(failure)
  }
)

/** multipart 可选字段：空值不发送（后端对空串/缺失的处理可能不同，按缺失传更稳） */
function appendOptional(fd, key, value) {
  if (value === undefined || value === null) return
  const text = String(value).trim()
  if (!text) return
  fd.append(key, text)
}

/** 通话时间兜底：日期选择器若给出 Date，转为后端约定的无时区 ISO 字符串 */
function toIsoLocal(value) {
  if (!(value instanceof Date) || Number.isNaN(value.getTime())) return value
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}` +
    `T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  )
}

/** 字符串查询参数：去空白，空串视为未传 */
function param(value) {
  if (value === undefined || value === null) return undefined
  const text = String(value).trim()
  return text || undefined
}

/**
 * 下载文件（CSV / JSON 等）统一入口（审计 P2-22）：后端成功返回文件流，失败可能返回
 * JSON 错误体（含 200 + JSON 的错误响应），不能把错误体当文件保存。
 *
 * 负责：
 * - 读取 `Content-Disposition` 的 filename（RFC 5987 中文名兼容）
 * - 识别 JSON 错误 Blob 并抛出可读 message（经 notifyError 统一提示）
 * - 返回 { blob, filename }，由调用方 saveBlob 触发浏览器保存（便于按钮 loading / 成功提示）
 *
 * @param {string} url 接口路径
 * @param {{params?: object, fallbackName?: string, jsonAsData?: boolean}} [options]
 *        jsonAsData=true 表示该接口成功响应本身即 JSON（如语料导出），合法 JSON 数组不视为错误体
 */
export async function requestBlob(
  url,
  { params, fallbackName = 'download.csv', jsonAsData = false } = {}
) {
  const resp = await http.get(url, { params, responseType: 'blob', rawResponse: true })
  const blob = resp.data
  const message = await blobErrorMessage(blob, { jsonAsData })
  if (message) {
    // 200 + JSON 错误体：不能当文件保存，提示后抛出
    notifyError(message)
    throw new Error(message)
  }
  const disposition = resp.headers?.['content-disposition'] || resp.headers?.['Content-Disposition']
  return { blob, filename: filenameFromDisposition(disposition, fallbackName) }
}

export const api = {
  // 认证（HttpOnly 会话 Cookie，前端不存 token）
  login(payload) {
    return http.post('/auth/login', payload)
  },
  logout() {
    return http.post('/auth/logout')
  },
  /** 当前登录用户；未登录返回 401 */
  me() {
    return http.get('/auth/me')
  },
  changePassword(payload) {
    return http.post('/auth/password', payload)
  },

  // 用户管理（ADMIN）
  listUsers() {
    return http.get('/users')
  },
  createUser(payload) {
    return http.post('/users', payload)
  },
  updateUser(id, payload) {
    return http.put(`/users/${id}`, payload)
  },
  /** 禁用（不物理删除，保留审计引用） */
  disableUser(id) {
    return http.delete(`/users/${id}`)
  },

  // 操作审计（ADMIN）
  listAuditLogs({ page, size, actor, action } = {}) {
    return http.get('/audit-logs', {
      params: { page, size, actor: actor || undefined, action: action || undefined }
    })
  },

  // AI 对话记录（ADMIN）：所有 AI 调用的提示词与原始回复
  /**
   * GET /api/ai-invocations?stage=&providerId=&model=&success=&recordingId=&from=&to=&page=&size=
   * -> 归一化 {items,total,page,size,totalPages}；列表不含完整提示词。
   */
  listAiInvocations({ page, size, stage, providerId, model, success, recordingId, from, to } = {}) {
    return http
      .get('/ai-invocations', {
        params: {
          page,
          size,
          stage: param(stage),
          providerId: param(providerId),
          model: param(model),
          success: typeof success === 'boolean' ? success : undefined,
          recordingId: param(recordingId),
          from: param(from),
          to: param(to)
        },
        silent: true
      })
      .then((data) => normalizeAiInvocationPage(data))
  },
  /** GET /api/ai-invocations/{id} -> 完整 requestJson/responseJson */
  getAiInvocation(id) {
    return http
      .get(`/ai-invocations/${encodeURIComponent(id)}`, { silent: true })
      .then((data) => normalizeAiInvocationDetail(data))
  },
  /** GET /api/ai-invocations/facets -> 筛选下拉候选 */
  getAiInvocationFacets() {
    return http
      .get('/ai-invocations/facets', { silent: true })
      .then((data) => normalizeAiInvocationFacets(data))
  },

  // 录音
  /**
   * 上传录音（multipart）。
   * @param {File} file 音频文件
   * @param {{agentName?:string, teamName?:string, callTime?:string, direction?:string, businessLine?:string}} metadata
   *        callTime 为 ISO-8601（如 2026-10-20T09:30:00），空值不发送
   * @param {{onUploadProgress?:Function, silent?:boolean}} [options]
   *        批量上传时逐文件进度回调；silent=true 时由调用方行内展示错误（批量失败汇总一条提示），
   *        不弹全局错误（审计 P2-33）。旧调用 `upload(file, metadata, {onUploadProgress})` 行为不变。
   */
  upload(file, metadata = {}, { onUploadProgress, silent } = {}) {
    const fd = new FormData()
    fd.append('file', file)
    appendOptional(fd, 'agentName', metadata.agentName)
    appendOptional(fd, 'teamName', metadata.teamName)
    appendOptional(fd, 'callTime', toIsoLocal(metadata.callTime))
    appendOptional(fd, 'direction', metadata.direction)
    appendOptional(fd, 'businessLine', metadata.businessLine)
    const config = {}
    if (typeof onUploadProgress === 'function') config.onUploadProgress = onUploadProgress
    if (silent === true) config.silent = true
    return http.post('/recordings/upload', fd, Object.keys(config).length ? config : undefined)
  },
  listRecordings({
    statuses,
    keyword,
    assignedTo,
    unassigned,
    priority,
    agentName,
    teamName,
    direction,
    from,
    to,
    page,
    size
  } = {}) {
    // 列表项透传后端 Summary 字段（含 sampled：DFA 未命中抽样标记），前端不做裁剪
    return http.get('/recordings', {
      params: {
        statuses: statuses?.join(',') || undefined,
        keyword: param(keyword),
        assignedTo: param(assignedTo),
        // 复检队列「未指派」筛选：后端按 assignedTo is null 过滤
        unassigned: unassigned || undefined,
        priority: param(priority),
        agentName: param(agentName),
        teamName: param(teamName),
        direction: param(direction),
        from: param(from),
        to: param(to),
        page,
        size
      }
    })
  },
  recordingDetail(id) {
    // 详情透传 sampled（抽样标记）等后端新增字段
    return http.get(`/recordings/${id}`)
  },
  recordingLogs(id) {
    return http.get(`/recordings/${id}/logs`)
  },
  /** AI 调用留痕：完整 prompt 与模型原始回复 */
  recordingAiInvocations(id) {
    return http.get(`/recordings/${id}/ai-invocations`)
  },
  /** 手动推进/重新处理（PENDING/FAILED/卡住的中间态，后端会做在途与状态守卫） */
  reprocessRecording(id) {
    return http.post(`/recordings/${id}/retry`)
  },
  deleteRecording(id) {
    return http.delete(`/recordings/${id}`)
  },

  // 异步任务队列（ADMIN）；silent 由任务页展示 403/404 降级提示
  /**
   * GET /api/tasks?status=&recordingId=&page=&size=
   * -> 归一化 {items,total,page,size,totalPages}（page 为 0 基，与后端一致）
   */
  listTasks({ status, recordingId, page, size } = {}) {
    return http
      .get('/tasks', {
        params: {
          status: param(status),
          recordingId: param(recordingId),
          page,
          size
        },
        silent: true
      })
      .then((data) => normalizeTaskPage(data))
  },
  /** POST /api/tasks/{id}/retry（仅 FAILED/DEAD）-> 归一化任务或 null（204） */
  retryTask(id) {
    return http
      .post(`/tasks/${encodeURIComponent(id)}/retry`, undefined, { silent: true })
      .then((data) => normalizeRetryResult(data))
  },

  // 复核工作流
  /** 认领：仅 NEEDS_REVIEW，已被他人认领返回 409 */
  claimRecording(id) {
    return http.post(`/recordings/${id}/claim`)
  },
  /** ADMIN 指派复核人 */
  assignRecording(id, username) {
    return http.put(`/recordings/${id}/assign`, { username })
  },
  /** ADMIN 重开终态录音 */
  reopenRecording(id) {
    return http.post(`/recordings/${id}/reopen`)
  },
  /** 复核历史（登录即可） */
  recordingReviews(id) {
    return http.get(`/recordings/${id}/reviews`)
  },

  // 人工复检：复核人由后端登录态决定（请求体不再传 reviewer）
  review(id, payload) {
    return http.post(`/reviews/${id}`, payload)
  },

  // 敏感词库
  listDictionary({ keyword, source, enabled, page, size }) {
    return http.get('/dictionary', {
      params: { keyword, source, enabled, page, size }
    })
  },
  createWord(payload) {
    return http.post('/dictionary', payload)
  },
  updateWord(id, payload) {
    return http.put(`/dictionary/${id}`, payload)
  },
  deleteWord(id) {
    return http.delete(`/dictionary/${id}`)
  },
  testText(text) {
    return http.post('/dictionary/test', { text })
  },
  /** 导出词库 CSV（ADMIN）；返回 { blob, filename } */
  exportDictionaryCsv() {
    return requestBlob('/dictionary/export', {
      fallbackName: `dictionary-${dateStamp()}.csv`
    })
  },
  /** 导入词库 CSV（ADMIN）→ { created, skipped, invalid } */
  importDictionary(file) {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/dictionary/import', fd)
  },
  /** 批量启用/停用（ADMIN）→ { updated } */
  bulkEnabled(ids, enabled) {
    return http.patch('/dictionary/bulk-enabled', { ids, enabled })
  },

  // 语料库
  listCorpus({ label, keyword, page, size }) {
    return http.get('/corpus', { params: { label, keyword, page, size } })
  },
  createCorpus(payload) {
    return http.post('/corpus', payload)
  },
  deleteCorpus(id) {
    return http.delete(`/corpus/${id}`)
  },
  /** 导出语料 JSON（登录即可）；返回 { blob, filename }，由调用方 saveBlob（P2-22 统一 blob 工具） */
  exportCorpus() {
    return requestBlob('/corpus/export', {
      fallbackName: `corpus-${dateStamp()}.json`,
      jsonAsData: true
    })
  },

  // 统计
  overview() {
    return http.get('/stats/overview')
  },
  /** 报表统计：from/to 可空（yyyy-MM-dd 或 ISO 日期时间）；响应含 sampled 抽样数 */
  statsReport({ from, to } = {}) {
    return http.get('/stats/report', { params: { from: param(from), to: param(to) } })
  },
  /**
   * AI 用量与成本（ADMIN）：from/to 可空（yyyy-MM-dd）。
   * 返回归一化的 {requests, inputTokens, outputTokens, costUsd, byDay[], byProvider[], byModel[]}；
   * silent：成本看板自行展示 403/404 错误与重试，不弹全局提示。
   */
  aiUsage({ from, to } = {}) {
    return http
      .get('/stats/ai-usage', {
        params: { from: param(from), to: param(to) },
        silent: true
      })
      .then((data) => normalizeAiUsage(data))
  },

  // 报表导出（登录即可）：返回 { blob, filename }，由调用方 saveBlob
  exportRecordingsCsv(params = {}) {
    return requestBlob('/reports/recordings.csv', {
      params: csvParams(params),
      fallbackName: `recordings-${dateStamp()}.csv`
    })
  },
  exportViolationsCsv(params = {}) {
    return requestBlob('/reports/violations.csv', {
      params: csvParams(params),
      fallbackName: `violations-${dateStamp()}.csv`
    })
  },
  exportReviewsCsv(params = {}) {
    return requestBlob('/reports/reviews.csv', {
      params: csvParams(params),
      fallbackName: `reviews-${dateStamp()}.csv`
    })
  },

  // 系统设置：AI 复筛模型（语义复筛 + 敏感词挖掘）
  /** GET /api/settings/screen-model -> 原字段 + 归一化 ab 配置（缺失为 null） */
  screenModel() {
    return http.get('/settings/screen-model').then((data) => normalizeScreenModel(data))
  },
  updateScreenModel(payload) {
    return http.put('/settings/screen-model', payload)
  },
  /** 连通性测试：传入 {providerId, model} 可测未保存的选择 */
  testScreenModel(payload = {}) {
    return http.post('/settings/screen-model/test', payload)
  },
  /**
   * 模型评测（ADMIN）：POST {providerId, model?, sampleSize?}（sampleSize 1~20，默认 10）
   * -> 归一化 {accuracy,precision,recall,tp,fp,tn,fn,total,evaluated,correct,failed,latencyMs,byLabel,results}
   * silent：卡内展示 403/404 降级与失败原因，不弹全局提示。
   */
  evaluateScreenModel(payload = {}) {
    return http
      .post('/settings/screen-model/evaluate', payload, { silent: true })
      .then((data) => normalizeEvaluation(data))
  },

  // 系统设置：RAG 检索增强（ADMIN）；读 / 测试 / 评测 silent，由设置页卡片展示 403/404 降级
  /** GET /api/settings/rag -> 归一化 {enabled, source, embedding, retrieval, index} */
  getRagSettings() {
    return http.get('/settings/rag', { silent: true }).then((data) => normalizeRagSettings(data))
  },
  /** PUT /api/settings/rag {enabled, topK, minScore, maxContextChars, corpusExamples} -> 同 GET */
  updateRagSettings(payload) {
    return http.put('/settings/rag', payload).then((data) => normalizeRagSettings(data))
  },
  /** POST /api/settings/rag/test -> 归一化 {ok, providerLabel, model, dimensions, latencyMs, message} */
  testRagEmbedding() {
    return http
      .post('/settings/rag/test', undefined, { silent: true })
      .then((data) => normalizeRagTest(data))
  },
  /** POST /api/settings/rag/reindex {scope:'knowledge'|'corpus'|'all'} -> {queued, skipped, message}（异步） */
  reindexRag(scope) {
    return http
      .post('/settings/rag/reindex', { scope })
      .then((data) => normalizeReindexResult(data))
  },
  /** POST /api/settings/rag/evaluate {sampleSize:1~20} -> 归一化 baseline / rag / delta / cases */
  evaluateRag(sampleSize) {
    return http
      .post('/settings/rag/evaluate', { sampleSize }, { silent: true })
      .then((data) => normalizeRagEvaluation(data))
  },

  // 系统设置：运营通知（ADMIN）；silent 由页面自行展示 403/404 的降级提示
  /** GET /api/settings/notifications -> 归一化的 {enabled, urlConfigured, backlogThreshold, samplingRate, events} */
  notificationSettings() {
    return http
      .get('/settings/notifications', { silent: true })
      .then((data) => normalizeNotificationSettings(data))
  },
  /** POST /api/settings/notifications/test -> 归一化的 {ok, latencyMs, message} */
  testNotification() {
    return http
      .post('/settings/notifications/test', undefined, { silent: true })
      .then((data) => normalizeNotificationTest(data))
  },

  // 系统设置：AI 供应商注册表（ADMIN）；列表/测试 silent，由设置页卡片内展示降级提示
  /** GET /api/settings/providers -> 归一化供应商数组（密钥只有掩码） */
  listProviders() {
    return http
      .get('/settings/providers', { silent: true })
      .then((data) => normalizeProviders(data))
  },
  /** POST /api/settings/providers（自定义供应商，key 唯一） */
  createProvider(payload) {
    return http.post('/settings/providers', payload)
  },
  /** PUT /api/settings/providers/{key}；payload.apiKey 为空时后端保持原密钥 */
  updateProvider(key, payload) {
    return http.put(`/settings/providers/${encodeURIComponent(key)}`, payload)
  },
  /** DELETE /api/settings/providers/{key}（仅自定义，且不能是当前生效供应商） */
  deleteProvider(key) {
    return http.delete(`/settings/providers/${encodeURIComponent(key)}`)
  },
  /** 连通性测试：model 可选，空表示使用该供应商默认模型 -> {ok, latencyMs, model, reply, message} */
  testProvider(key, model) {
    return http
      .post(
        `/settings/providers/${encodeURIComponent(key)}/test`,
        { model: param(model) },
        { silent: true }
      )
      .then((data) => normalizeProviderTest(data))
  },

  // 开放 API 密钥（ADMIN）；列表 silent，由页面展示 403/404 降级提示
  /**
   * GET /api/api-keys -> 归一化 {items,total,page,size,totalPages}
   * 列表只含掩码（prefix/last4），不含明文。
   */
  listApiKeys() {
    return http.get('/api-keys', { silent: true }).then((data) => normalizeApiKeyList(data))
  },
  /** POST /api/api-keys {name} -> 创建响应含一次性明文 key（之后不可再查看） */
  createApiKey(payload) {
    return http.post('/api-keys', payload).then((data) => normalizeApiKey(data))
  },
  /** DELETE /api/api-keys/{id}：停用（不物理删除，保留审计线索） */
  revokeApiKey(id) {
    return http.delete(`/api-keys/${encodeURIComponent(id)}`)
  },

  // 知识库（登录可读；写操作 ADMIN / REVIEWER，后端独立鉴权）
  /**
   * GET /api/knowledge?keyword=&category=&enabled=&indexStatus=&sourceType=&page=&size=
   * -> 归一化 Spring Page（keyword 同时模糊匹配标题 / 正文 / 标签 / 来源文件）
   */
  listKnowledge({ keyword, category, enabled, indexStatus, sourceType, page, size } = {}) {
    return http
      .get('/knowledge', {
        params: {
          keyword: param(keyword),
          category: param(category),
          enabled: param(enabled),
          indexStatus: param(indexStatus),
          sourceType: param(sourceType),
          page,
          size
        },
        silent: true
      })
      .then((data) => normalizeKnowledgePage(data))
  },
  /**
   * GET /api/knowledge/{id}/chunks?page=&size=（ADMIN）-> 归一化条目信息 + 当前页分块
   * silent：分块对话框内展示 403/404 降级与失败原因，不弹全局提示。
   */
  listKnowledgeChunks(id, { page, size } = {}) {
    return http
      .get(`/knowledge/${encodeURIComponent(id)}/chunks`, {
        params: { page, size },
        silent: true
      })
      .then((data) => normalizeKnowledgeChunks(data))
  },
  createKnowledge(payload) {
    return http.post('/knowledge', payload).then((data) => normalizeKnowledgeEntry(data))
  },
  updateKnowledge(id, payload) {
    return http
      .put(`/knowledge/${encodeURIComponent(id)}`, payload)
      .then((data) => normalizeKnowledgeEntry(data))
  },
  deleteKnowledge(id) {
    return http.delete(`/knowledge/${encodeURIComponent(id)}`)
  },
  /**
   * 语义检索预览（ADMIN / REVIEWER）：POST {query, topK}（topK 约定 1~10）
   * -> 归一化 {query, results:[{chunkId,entryId,title,category,content,score}]}
   * silent：检索预览对话框内展示 403/404 降级与失败原因，不弹全局提示。
   */
  searchKnowledge({ query, topK } = {}) {
    return http
      .post('/knowledge/search', { query, topK }, { silent: true })
      .then((data) => normalizeKnowledgeSearch(data))
  },
  /**
   * 导入文档（ADMIN / REVIEWER，multipart/form-data）：字段 file 必填，title/category 可选。
   * 返回 201 条目（indexStatus=PENDING，后台异步解析 + 索引）；重复文档 409 由请求层统一提示。
   */
  uploadKnowledgeDocument(file, { title, category } = {}) {
    const fd = new FormData()
    fd.append('file', file)
    appendOptional(fd, 'title', title)
    appendOptional(fd, 'category', category)
    return http.post('/knowledge/documents', fd).then((data) => normalizeKnowledgeEntry(data))
  },
  /** 重建单条知识库索引（ADMIN / REVIEWER）-> {id, status}（异步执行） */
  reindexKnowledge(id) {
    return http.post(`/knowledge/${encodeURIComponent(id)}/reindex`)
  }
}

/** 导出参数：与列表筛选同名，去掉空值 */
function csvParams(params = {}) {
  const out = {}
  for (const [key, value] of Object.entries(params)) {
    const normalized = param(value)
    if (normalized !== undefined) out[key] = normalized
  }
  return out
}

export default api
