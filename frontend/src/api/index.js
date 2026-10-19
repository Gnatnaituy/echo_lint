import axios from 'axios'
import { ElMessage } from 'element-plus'
import { blobErrorMessage, dateStamp, filenameFromDisposition } from '../utils/download'
import {
  normalizeNotificationSettings,
  normalizeNotificationTest
} from '../utils/notifications'
import {
  normalizeProviderTest,
  normalizeProviders
} from '../utils/providers'
import { normalizeAiUsage } from '../utils/aiUsage'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  // 会话认证：JSESSIONID 为 HttpOnly Cookie，由浏览器自动携带（前端不存 token）
  withCredentials: true
})

const GENERIC_ERROR = '请求失败，请检查后端服务是否可用'

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
 */
async function pickMessage(error) {
  const data = error.response?.data
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const text = (await data.text()).trim()
      if (text) {
        try {
          const parsed = JSON.parse(text)
          return payloadMessage(parsed) || error.message || GENERIC_ERROR
        } catch {
          // 非 JSON（如 nginx 的 HTML 错误页）：不直接回显，避免污染界面
        }
      }
    } catch {
      // 读取 Blob 失败：退回 axios 自身 message
    }
    return error.message || GENERIC_ERROR
  }
  return payloadMessage(data) || error.message || GENERIC_ERROR
}

/**
 * 401 统一处理：清理本地登录态并跳转登录页（携带原始地址，登录后回跳）。
 * 动态引入 router / auth，避免 api ←→ router/auth 的循环依赖。
 */
async function handleUnauthorized(error) {
  const [{ default: auth }, { default: router }] = await Promise.all([
    import('../auth'),
    import('../router')
  ])
  auth.clear()
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    const full = current.fullPath || ''
    const redirect = full && full !== '/' && !full.startsWith('/login') ? full : undefined
    router.push({ path: '/login', query: redirect ? { redirect } : {} })
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
      if (!silent) ElMessage.error('无权限执行该操作')
      const denied = new Error('无权限执行该操作')
      denied.status = 403
      return Promise.reject(denied)
    }

    const msg = await pickMessage(error)
    if (!silent) ElMessage.error(msg)
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
 * 下载 CSV：后端成功返回文件流，失败返回 JSON 错误体。
 * 返回 { blob, filename }，由调用方通过 saveBlob 触发浏览器保存（便于页面提示）。
 */
async function requestCsv(url, params, fallbackName) {
  const resp = await http.get(url, { params, responseType: 'blob', rawResponse: true })
  const blob = resp.data
  const message = await blobErrorMessage(blob)
  if (message) {
    // 200 + JSON 错误体：不能当 CSV 保存，提示后抛出
    ElMessage.error(message)
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

  // 录音
  /**
   * 上传录音（multipart）。
   * @param {File} file 音频文件
   * @param {{agentName?:string, teamName?:string, callTime?:string, direction?:string, businessLine?:string}} metadata
   *        callTime 为 ISO-8601（如 2026-10-20T09:30:00），空值不发送
   */
  upload(file, metadata = {}) {
    const fd = new FormData()
    fd.append('file', file)
    appendOptional(fd, 'agentName', metadata.agentName)
    appendOptional(fd, 'teamName', metadata.teamName)
    appendOptional(fd, 'callTime', toIsoLocal(metadata.callTime))
    appendOptional(fd, 'direction', metadata.direction)
    appendOptional(fd, 'businessLine', metadata.businessLine)
    return http.post('/recordings/upload', fd)
  },
  listRecordings({ statuses, keyword, assignedTo, priority, agentName, teamName, direction, from, to, page, size } = {}) {
    // 列表项透传后端 Summary 字段（含 sampled：DFA 未命中抽样标记），前端不做裁剪
    return http.get('/recordings', {
      params: {
        statuses: statuses?.join(',') || undefined,
        keyword: param(keyword),
        assignedTo: param(assignedTo),
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
    return requestCsv('/dictionary/export', undefined, `dictionary-${dateStamp()}.csv`)
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
  exportCorpus() {
    return http.get('/corpus/export', { responseType: 'blob' })
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
    return requestCsv('/reports/recordings.csv', csvParams(params), `recordings-${dateStamp()}.csv`)
  },
  exportViolationsCsv(params = {}) {
    return requestCsv('/reports/violations.csv', csvParams(params), `violations-${dateStamp()}.csv`)
  },
  exportReviewsCsv(params = {}) {
    return requestCsv('/reports/reviews.csv', csvParams(params), `reviews-${dateStamp()}.csv`)
  },

  // 系统设置：AI 复筛模型（语义复筛 + 敏感词挖掘）
  screenModel() {
    return http.get('/settings/screen-model')
  },
  updateScreenModel(payload) {
    return http.put('/settings/screen-model', payload)
  },
  /** 连通性测试：传入 {providerId, model} 可测未保存的选择 */
  testScreenModel(payload = {}) {
    return http.post('/settings/screen-model/test', payload)
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
