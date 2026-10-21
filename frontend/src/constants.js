// 录音状态元数据
export const STATUS_META = {
  PENDING: { label: '待处理', type: 'info' },
  TRANSCRIBING: { label: '转写中', type: 'warning' },
  DFA_CHECKING: { label: '初筛中', type: 'warning' },
  AI_CHECKING: { label: '语义复筛中', type: 'warning' },
  NEEDS_REVIEW: { label: '待人工复检', type: 'danger' },
  COMPLIANT: { label: '自动通过', type: 'success' },
  VIOLATION_CONFIRMED: { label: '确认违规', type: 'danger' },
  FALSE_POSITIVE: { label: '误判放行', type: 'info' },
  FAILED: { label: '处理失败', type: 'danger' }
}

export const PROCESSING_STATUSES = ['PENDING', 'TRANSCRIBING', 'DFA_CHECKING', 'AI_CHECKING']

export const VIOLATION_TYPES = {
  INSULT: '辱骂',
  DISCRIMINATION: '歧视',
  THREAT: '威胁',
  HARASSMENT: '骚扰',
  SEXUAL: '色情骚扰',
  FRAUD: '诈骗诱导',
  PRIVACY: '隐私泄露',
  OTHER: '其他'
}

export const SEVERITY_META = {
  HIGH: { label: '高', type: 'danger' },
  MEDIUM: { label: '中', type: 'warning' },
  LOW: { label: '低', type: 'info' }
}

export const SOURCE_META = {
  MANUAL: { label: '人工录入', type: 'primary' },
  MINED: { label: 'AI 挖掘', type: 'warning' }
}

/* ==========================================================================
   录音元数据：通话方向
   ========================================================================== */

/** 通话方向：后端枚举 INBOUND/OUTBOUND/UNKNOWN（历史数据缺省 UNKNOWN） */
export const DIRECTION_META = {
  INBOUND: { label: '呼入', type: 'brand' },
  OUTBOUND: { label: '呼出', type: 'warn' },
  UNKNOWN: { label: '未知', type: 'info' }
}

export const DIRECTION_OPTIONS = Object.keys(DIRECTION_META).map((value) => ({
  value,
  label: DIRECTION_META[value].label
}))

export function directionMeta(direction) {
  return DIRECTION_META[direction] || null
}

export function directionLabel(direction) {
  return directionMeta(direction)?.label || (direction ? String(direction) : '—')
}

/* ==========================================================================
   身份 / 复核工作流 / 操作审计
   ========================================================================== */

/** 角色元数据：前端只用于展示与隐藏入口，权限由后端独立校验 */
export const ROLE_META = {
  ADMIN: { label: '管理员', short: '管理', type: 'danger', description: '用户管理、操作审计、配置与复核' },
  REVIEWER: { label: '复核员', short: '复核', type: 'warning', description: '上传录音、认领与提交复核结论' },
  VIEWER: { label: '只读', short: '只读', type: 'info', description: '只读访问，不执行写操作' }
}

export const ROLE_OPTIONS = Object.keys(ROLE_META).map((value) => ({
  value,
  label: ROLE_META[value].label
}))

export function roleLabel(role) {
  return ROLE_META[role]?.label || (role ? String(role) : '—')
}

/** 复核优先级：后端默认 NORMAL，未知值按 NORMAL 处理 */
export const PRIORITY_META = {
  HIGH: { label: '高', type: 'danger', order: 0 },
  NORMAL: { label: '普通', type: 'primary', order: 1 },
  LOW: { label: '低', type: 'info', order: 2 }
}

export const PRIORITY_OPTIONS = Object.keys(PRIORITY_META)
  .sort((a, b) => PRIORITY_META[a].order - PRIORITY_META[b].order)
  .map((value) => ({ value, label: PRIORITY_META[value].label }))

export function priorityMeta(priority) {
  return PRIORITY_META[priority] || PRIORITY_META.NORMAL
}

/** 复核终态：ADMIN 可重开为 NEEDS_REVIEW */
export const FINAL_REVIEW_STATUSES = ['VIOLATION_CONFIRMED', 'FALSE_POSITIVE', 'COMPLIANT']

export function isFinalReviewStatus(status) {
  return FINAL_REVIEW_STATUSES.includes(status)
}

/**
 * 是否抽样录音：DFA 未命中时按配置比例进入 AI 语义复筛 / 人工复检的合规抽检。
 * 后端契约是 boolean，这里兼容 0/1 与字符串；缺字段 / 未知值一律视为未抽样。
 */
export function isSampled(record) {
  const value = record?.sampled
  return value === true || value === 1 || value === '1' || value === 'true' || value === 'TRUE'
}

/* ==========================================================================
   异步任务队列（pipeline_tasks）
   ========================================================================== */

/** 任务状态：PENDING 排队待执行（含退避等待）/ RUNNING 执行中 / SUCCEEDED 成功 / FAILED 失败等待下次重试 / DEAD 达到最大次数（死信，仅手动重试） */
export const TASK_STATUS_META = {
  PENDING: { label: '排队中', type: 'info' },
  RUNNING: { label: '执行中', type: 'warn' },
  SUCCEEDED: { label: '已成功', type: 'ok' },
  FAILED: { label: '等待重试', type: 'warn' },
  DEAD: { label: '最终失败', type: 'danger' }
}

export const TASK_STATUS_OPTIONS = Object.keys(TASK_STATUS_META).map((value) => ({
  value,
  label: TASK_STATUS_META[value].label
}))

export function taskStatusMeta(status) {
  return TASK_STATUS_META[status] || { label: status ? String(status) : '—', type: 'info' }
}

/** 后端仅允许 FAILED / DEAD 手动重试（重排为 PENDING 且 attempts 归零） */
export function isTaskRetryable(status) {
  return status === 'FAILED' || status === 'DEAD'
}

/**
 * 复核进度：reviewCount / requiredApprovals。
 * 后端缺省时为单人复核（required=1），前端只做展示与提交提示。
 */
export function reviewProgress(record) {
  const count = Math.max(0, Number(record?.reviewCount) || 0)
  const required = Math.max(1, Number(record?.requiredApprovals) || 1)
  return {
    count,
    required,
    done: count >= required,
    remaining: Math.max(0, required - count),
    text: `${count}/${required}`
  }
}

/** 操作审计动作标签 */
export const AUDIT_ACTION_META = {
  LOGIN: { label: '登录', type: 'primary' },
  LOGOUT: { label: '退出登录', type: 'info' },
  USER_CREATE: { label: '创建用户', type: 'success' },
  USER_UPDATE: { label: '更新用户', type: 'warning' },
  USER_DISABLE: { label: '禁用用户', type: 'danger' },
  RECORDING_UPLOAD: { label: '上传录音', type: 'primary' },
  RECORDING_DELETE: { label: '删除录音', type: 'danger' },
  RECORDING_RETRY: { label: '重试处理', type: 'warning' },
  TASK_RETRY: { label: '重试任务', type: 'warning' },
  RECORDING_CLAIM: { label: '认领录音', type: 'primary' },
  RECORDING_ASSIGN: { label: '指派录音', type: 'primary' },
  REVIEW_SUBMIT: { label: '提交复核', type: 'success' },
  REVIEW_REOPEN: { label: '重开复核', type: 'warning' },
  DICTIONARY_CREATE: { label: '新增敏感词', type: 'success' },
  DICTIONARY_UPDATE: { label: '更新敏感词', type: 'warning' },
  DICTIONARY_DELETE: { label: '删除敏感词', type: 'danger' },
  DICTIONARY_IMPORT: { label: '导入敏感词', type: 'success' },
  DICTIONARY_BULK_UPDATE: { label: '批量启停敏感词', type: 'warning' },
  CORPUS_CREATE: { label: '新增语料', type: 'success' },
  CORPUS_DELETE: { label: '删除语料', type: 'danger' },
  REPORT_EXPORT: { label: '报表导出', type: 'primary' },
  SETTINGS_UPDATE: { label: '修改设置', type: 'warning' },
  NOTIFY_TEST: { label: '测试通知', type: 'info' },
  PROVIDER_CREATE: { label: '新增 AI 供应商', type: 'success' },
  PROVIDER_UPDATE: { label: '更新 AI 供应商', type: 'warning' },
  PROVIDER_DELETE: { label: '删除 AI 供应商', type: 'danger' },
  PROVIDER_TEST: { label: '测试 AI 供应商', type: 'info' },
  MODEL_EVALUATE: { label: '模型评测', type: 'info' },
  API_KEY_CREATE: { label: '创建 API 密钥', type: 'success' },
  API_KEY_REVOKE: { label: '停用 API 密钥', type: 'danger' },
  OPEN_API_UPLOAD: { label: '开放 API 上传录音', type: 'primary' },
  KNOWLEDGE_CREATE: { label: '新增知识库条目', type: 'success' },
  KNOWLEDGE_UPDATE: { label: '更新知识库条目', type: 'warning' },
  KNOWLEDGE_DELETE: { label: '删除知识库条目', type: 'danger' },
  RAG_SETTINGS_UPDATE: { label: '修改 RAG 设置', type: 'warning' },
  RAG_REINDEX: { label: '重建 RAG 索引', type: 'info' },
  RAG_EVALUATE: { label: 'RAG 对比评测', type: 'info' },
  RAG_DOCUMENT_IMPORT: { label: '导入知识库文档', type: 'success' },
  PASSWORD_CHANGE: { label: '修改密码', type: 'warning' }
}

export const AUDIT_ACTION_OPTIONS = Object.keys(AUDIT_ACTION_META).map((value) => ({
  value,
  label: AUDIT_ACTION_META[value].label
}))

export function auditActionMeta(action) {
  return AUDIT_ACTION_META[action] || { label: action || '—', type: 'info' }
}

export function auditActionLabel(action) {
  return auditActionMeta(action).label
}

/* ==========================================================================
   运营通知（Webhook）
   ========================================================================== */

/** 通知事件元数据：与后端 NotificationService 的事件名一一对应，未知事件回退原始 code */
export const NOTIFY_EVENT_META = {
  REVIEW_CONFIRMED_VIOLATION: { label: '复核确认违规', type: 'danger' },
  RECORDING_FAILED: { label: '录音处理失败', type: 'warn' },
  REVIEW_BACKLOG: { label: '待复核积压', type: 'warn' },
  NOTIFY_TEST: { label: '测试事件', type: 'info' }
}

export function notifyEventMeta(event) {
  return NOTIFY_EVENT_META[event] || { label: event ? String(event) : '—', type: 'info' }
}

/**
 * 智能时间：今天 HH:mm / 昨天 HH:mm / 今年 MM-DD HH:mm / 更早 YYYY-MM-DD HH:mm。
 * 后端 LocalDateTime 无时区（如 2026-10-20T10:30:00），按浏览器本地时间解析。
 */
export function formatSmartTime(ts, now = Date.now()) {
  if (!ts) return '—'
  const d = new Date(String(ts).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(ts).replace('T', ' ').substring(0, 19)
  const nowDate = new Date(now)
  const pad = (n) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  if (sameDay(d, nowDate)) return `今天 ${hm}`
  const yesterday = new Date(nowDate)
  yesterday.setDate(yesterday.getDate() - 1)
  if (sameDay(d, yesterday)) return `昨天 ${hm}`
  const md = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return d.getFullYear() === nowDate.getFullYear() ? `${md} ${hm}` : `${d.getFullYear()}-${md} ${hm}`
}

/** 登录后跳转目标：仅允许站内绝对路径，避免开放重定向 */
export function safeRedirect(target, fallback = '/') {
  if (typeof target !== 'string') return fallback
  const path = target.trim()
  if (!path.startsWith('/') || path.startsWith('//')) return fallback
  if (path === '/login' || path.startsWith('/login?') || path.startsWith('/login/')) return fallback
  return path
}

export function formatDuration(seconds) {
  if (!seconds) return '-'
  const m = Math.floor(seconds / 60)
  const s = Math.round(seconds % 60)
  return m > 0 ? `${m}分${s}秒` : `${s}秒`
}

export function formatSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

export function formatTime(ts) {
  if (!ts) return '-'
  return ts.replace('T', ' ').substring(0, 19)
}

/** 紧凑时间：MM-DD HH:mm */
export function formatTimeShort(ts) {
  if (!ts) return '-'
  const s = ts.replace('T', ' ')
  return s.length >= 16 ? s.substring(5, 16) : s
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / MM-DD HH:mm */
export function formatRelative(ts) {
  if (!ts) return '-'
  const then = new Date(ts.replace(' ', 'T'))
  if (Number.isNaN(then.getTime())) return formatTimeShort(ts)
  const diff = Date.now() - then.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day === 1) return `昨天 ${String(then.getHours()).padStart(2, '0')}:${String(then.getMinutes()).padStart(2, '0')}`
  if (day < 7) return `${day} 天前`
  return formatTimeShort(ts)
}

/** 中间态超过该时长仍未变更 → 视为卡住 */
export const STALE_MS = 2 * 60 * 1000

/** 处理是否疑似卡住（仅对进行中的状态有意义） */
export function isStale(row, now = Date.now()) {
  if (!row || !PROCESSING_STATUSES.includes(row.status)) return false
  const ts = row.statusUpdatedAt || row.uploadTime
  if (!ts) return false
  const then = new Date(String(ts).replace(' ', 'T')).getTime()
  if (Number.isNaN(then)) return false
  return now - then > STALE_MS
}

/**
 * 手动推进/重新处理的按钮信息；返回 null 表示该状态不该出现此按钮。
 * - PENDING：排队未开始（如上传后服务重启），可立即推进
 * - 转写中/初筛中/复筛中：仅在疑似卡住时给「重新处理」
 * - 失败：给「重试」
 * - 已有结论（自动通过/待复检/确认违规/误判放行）：不给，避免覆盖结论
 */
export function reprocessInfo(row, now = Date.now()) {
  const status = row?.status
  if (!status) return null

  if (status === 'FAILED') {
    return {
      label: '重试',
      tone: 'warning',
      confirm: '重新执行转写与稽核流程？（将覆盖失败记录）'
    }
  }
  if (status === 'PENDING') {
    const stale = isStale(row, now)
    return {
      label: stale ? '推进处理' : '立即处理',
      tone: 'primary',
      confirm: stale
        ? '该录音排队超过 2 分钟仍未开始，可能是上传后服务重启导致流水线丢失。立即处理？'
        : '立即开始处理该录音？（正在排队时重复触发会被后端忽略）'
    }
  }
  if (PROCESSING_STATUSES.includes(status)) {
    if (!isStale(row, now)) return null
    return {
      label: '重新处理',
      tone: 'warning',
      confirm: `该录音停留在「${STATUS_META[status]?.label || status}」超过 2 分钟，疑似中断。重新处理？`
    }
  }
  return null
}
