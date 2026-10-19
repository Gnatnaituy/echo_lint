import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  // 会话认证：JSESSIONID 为 HttpOnly Cookie，由浏览器自动携带（前端不存 token）
  withCredentials: true
})

const GENERIC_ERROR = '请求失败，请检查后端服务是否可用'

function pickMessage(error) {
  return error.response?.data?.message || error.message || GENERIC_ERROR
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
  return Promise.reject(new Error(pickMessage(error)))
}

http.interceptors.response.use(
  (resp) => resp.data,
  async (error) => {
    const status = error.response?.status
    const url = String(error.config?.url || '')

    if (status === 401) {
      // 登录失败 / 会话探测失败交给调用方处理：不弹全局提示、不重复跳转
      if (url.includes('/auth/login') || url.includes('/auth/me')) {
        return Promise.reject(new Error(pickMessage(error)))
      }
      return handleUnauthorized(error)
    }

    if (status === 403) {
      ElMessage.error('无权限执行该操作')
      return Promise.reject(new Error('无权限执行该操作'))
    }

    const msg = pickMessage(error)
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  }
)

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
  upload(file) {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/recordings/upload', fd)
  },
  listRecordings({ statuses, keyword, assignedTo, priority, page, size }) {
    return http.get('/recordings', {
      params: {
        statuses: statuses?.join(',') || undefined,
        keyword,
        assignedTo,
        priority,
        page,
        size
      }
    })
  },
  recordingDetail(id) {
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
  }
}

export default api
