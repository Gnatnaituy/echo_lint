// 录音状态元数据
//
// 标签统一用 getter 读取 i18n：模板 / 计算属性读到 `.label` 时会依赖响应式 locale，
// 切换语言后自动更新；测试环境默认中文（与词典中文回退一致），输出不变。
import { t, tn } from './i18n/index.js'

export const STATUS_META = {
  PENDING: { get label() { return t('meta.status.PENDING') }, type: 'info' },
  TRANSCRIBING: { get label() { return t('meta.status.TRANSCRIBING') }, type: 'warning' },
  DFA_CHECKING: { get label() { return t('meta.status.DFA_CHECKING') }, type: 'warning' },
  AI_CHECKING: { get label() { return t('meta.status.AI_CHECKING') }, type: 'warning' },
  NEEDS_REVIEW: { get label() { return t('meta.status.NEEDS_REVIEW') }, type: 'danger' },
  COMPLIANT: { get label() { return t('meta.status.COMPLIANT') }, type: 'success' },
  VIOLATION_CONFIRMED: { get label() { return t('meta.status.VIOLATION_CONFIRMED') }, type: 'danger' },
  FALSE_POSITIVE: { get label() { return t('meta.status.FALSE_POSITIVE') }, type: 'info' },
  FAILED: { get label() { return t('meta.status.FAILED') }, type: 'danger' }
}

export const PROCESSING_STATUSES = ['PENDING', 'TRANSCRIBING', 'DFA_CHECKING', 'AI_CHECKING']

export const VIOLATION_TYPES = {
  get INSULT() { return t('meta.violation.INSULT') },
  get DISCRIMINATION() { return t('meta.violation.DISCRIMINATION') },
  get THREAT() { return t('meta.violation.THREAT') },
  get HARASSMENT() { return t('meta.violation.HARASSMENT') },
  get SEXUAL() { return t('meta.violation.SEXUAL') },
  get FRAUD() { return t('meta.violation.FRAUD') },
  get PRIVACY() { return t('meta.violation.PRIVACY') },
  get OTHER() { return t('meta.violation.OTHER') }
}

export const SEVERITY_META = {
  HIGH: { get label() { return t('meta.severity.HIGH') }, type: 'danger' },
  MEDIUM: { get label() { return t('meta.severity.MEDIUM') }, type: 'warning' },
  LOW: { get label() { return t('meta.severity.LOW') }, type: 'info' }
}

export const SOURCE_META = {
  MANUAL: { get label() { return t('meta.source.MANUAL') }, type: 'primary' },
  MINED: { get label() { return t('meta.source.MINED') }, type: 'warning' }
}

/* ==========================================================================
   录音元数据：通话方向
   ========================================================================== */

/** 通话方向：后端枚举 INBOUND/OUTBOUND/UNKNOWN（历史数据缺省 UNKNOWN） */
export const DIRECTION_META = {
  INBOUND: { get label() { return t('meta.direction.INBOUND') }, type: 'brand' },
  OUTBOUND: { get label() { return t('meta.direction.OUTBOUND') }, type: 'warn' },
  UNKNOWN: { get label() { return t('meta.direction.UNKNOWN') }, type: 'info' }
}

/** 下拉选项：label 为 getter（跟随语言），value 保持后端枚举值 */
function option(value, key) {
  return {
    value,
    get label() {
      return t(key)
    }
  }
}

export const DIRECTION_OPTIONS = Object.keys(DIRECTION_META).map((value) =>
  option(value, `meta.direction.${value}`)
)

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
  ADMIN: {
    get label() { return t('meta.role.ADMIN') },
    get short() { return t('meta.roleShort.ADMIN') },
    type: 'danger',
    get description() { return t('meta.roleDescription.ADMIN') }
  },
  REVIEWER: {
    get label() { return t('meta.role.REVIEWER') },
    get short() { return t('meta.roleShort.REVIEWER') },
    type: 'warning',
    get description() { return t('meta.roleDescription.REVIEWER') }
  },
  VIEWER: {
    get label() { return t('meta.role.VIEWER') },
    get short() { return t('meta.roleShort.VIEWER') },
    type: 'info',
    get description() { return t('meta.roleDescription.VIEWER') }
  }
}

export const ROLE_OPTIONS = Object.keys(ROLE_META).map((value) => option(value, `meta.role.${value}`))

export function roleLabel(role) {
  return ROLE_META[role]?.label || (role ? String(role) : '—')
}

/** 复核优先级：后端默认 NORMAL，未知值按 NORMAL 处理 */
export const PRIORITY_META = {
  HIGH: { get label() { return t('meta.priority.HIGH') }, type: 'danger', order: 0 },
  NORMAL: { get label() { return t('meta.priority.NORMAL') }, type: 'primary', order: 1 },
  LOW: { get label() { return t('meta.priority.LOW') }, type: 'info', order: 2 }
}

export const PRIORITY_OPTIONS = Object.keys(PRIORITY_META)
  .sort((a, b) => PRIORITY_META[a].order - PRIORITY_META[b].order)
  .map((value) => option(value, `meta.priority.${value}`))

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
  PENDING: { get label() { return t('meta.taskStatus.PENDING') }, type: 'info' },
  RUNNING: { get label() { return t('meta.taskStatus.RUNNING') }, type: 'warn' },
  SUCCEEDED: { get label() { return t('meta.taskStatus.SUCCEEDED') }, type: 'ok' },
  FAILED: { get label() { return t('meta.taskStatus.FAILED') }, type: 'warn' },
  DEAD: { get label() { return t('meta.taskStatus.DEAD') }, type: 'danger' }
}

export const TASK_STATUS_OPTIONS = Object.keys(TASK_STATUS_META).map((value) =>
  option(value, `meta.taskStatus.${value}`)
)

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
  LOGIN: { get label() { return t('meta.auditAction.LOGIN') }, type: 'primary' },
  LOGOUT: { get label() { return t('meta.auditAction.LOGOUT') }, type: 'info' },
  USER_CREATE: { get label() { return t('meta.auditAction.USER_CREATE') }, type: 'success' },
  USER_UPDATE: { get label() { return t('meta.auditAction.USER_UPDATE') }, type: 'warning' },
  USER_DISABLE: { get label() { return t('meta.auditAction.USER_DISABLE') }, type: 'danger' },
  RECORDING_UPLOAD: { get label() { return t('meta.auditAction.RECORDING_UPLOAD') }, type: 'primary' },
  RECORDING_DELETE: { get label() { return t('meta.auditAction.RECORDING_DELETE') }, type: 'danger' },
  RECORDING_RETRY: { get label() { return t('meta.auditAction.RECORDING_RETRY') }, type: 'warning' },
  TASK_RETRY: { get label() { return t('meta.auditAction.TASK_RETRY') }, type: 'warning' },
  RECORDING_CLAIM: { get label() { return t('meta.auditAction.RECORDING_CLAIM') }, type: 'primary' },
  RECORDING_ASSIGN: { get label() { return t('meta.auditAction.RECORDING_ASSIGN') }, type: 'primary' },
  REVIEW_SUBMIT: { get label() { return t('meta.auditAction.REVIEW_SUBMIT') }, type: 'success' },
  REVIEW_REOPEN: { get label() { return t('meta.auditAction.REVIEW_REOPEN') }, type: 'warning' },
  DICTIONARY_CREATE: { get label() { return t('meta.auditAction.DICTIONARY_CREATE') }, type: 'success' },
  DICTIONARY_UPDATE: { get label() { return t('meta.auditAction.DICTIONARY_UPDATE') }, type: 'warning' },
  DICTIONARY_DELETE: { get label() { return t('meta.auditAction.DICTIONARY_DELETE') }, type: 'danger' },
  DICTIONARY_IMPORT: { get label() { return t('meta.auditAction.DICTIONARY_IMPORT') }, type: 'success' },
  DICTIONARY_BULK_UPDATE: { get label() { return t('meta.auditAction.DICTIONARY_BULK_UPDATE') }, type: 'warning' },
  CORPUS_CREATE: { get label() { return t('meta.auditAction.CORPUS_CREATE') }, type: 'success' },
  CORPUS_DELETE: { get label() { return t('meta.auditAction.CORPUS_DELETE') }, type: 'danger' },
  REPORT_EXPORT: { get label() { return t('meta.auditAction.REPORT_EXPORT') }, type: 'primary' },
  SETTINGS_UPDATE: { get label() { return t('meta.auditAction.SETTINGS_UPDATE') }, type: 'warning' },
  NOTIFY_TEST: { get label() { return t('meta.auditAction.NOTIFY_TEST') }, type: 'info' },
  PROVIDER_CREATE: { get label() { return t('meta.auditAction.PROVIDER_CREATE') }, type: 'success' },
  PROVIDER_UPDATE: { get label() { return t('meta.auditAction.PROVIDER_UPDATE') }, type: 'warning' },
  PROVIDER_DELETE: { get label() { return t('meta.auditAction.PROVIDER_DELETE') }, type: 'danger' },
  PROVIDER_TEST: { get label() { return t('meta.auditAction.PROVIDER_TEST') }, type: 'info' },
  MODEL_EVALUATE: { get label() { return t('meta.auditAction.MODEL_EVALUATE') }, type: 'info' },
  API_KEY_CREATE: { get label() { return t('meta.auditAction.API_KEY_CREATE') }, type: 'success' },
  API_KEY_REVOKE: { get label() { return t('meta.auditAction.API_KEY_REVOKE') }, type: 'danger' },
  OPEN_API_UPLOAD: { get label() { return t('meta.auditAction.OPEN_API_UPLOAD') }, type: 'primary' },
  KNOWLEDGE_CREATE: { get label() { return t('meta.auditAction.KNOWLEDGE_CREATE') }, type: 'success' },
  KNOWLEDGE_UPDATE: { get label() { return t('meta.auditAction.KNOWLEDGE_UPDATE') }, type: 'warning' },
  KNOWLEDGE_DELETE: { get label() { return t('meta.auditAction.KNOWLEDGE_DELETE') }, type: 'danger' },
  RAG_SETTINGS_UPDATE: { get label() { return t('meta.auditAction.RAG_SETTINGS_UPDATE') }, type: 'warning' },
  RAG_REINDEX: { get label() { return t('meta.auditAction.RAG_REINDEX') }, type: 'info' },
  RAG_EVALUATE: { get label() { return t('meta.auditAction.RAG_EVALUATE') }, type: 'info' },
  RAG_DOCUMENT_IMPORT: { get label() { return t('meta.auditAction.RAG_DOCUMENT_IMPORT') }, type: 'success' },
  PASSWORD_CHANGE: { get label() { return t('meta.auditAction.PASSWORD_CHANGE') }, type: 'warning' }
}

export const AUDIT_ACTION_OPTIONS = Object.keys(AUDIT_ACTION_META).map((value) =>
  option(value, `meta.auditAction.${value}`)
)

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
  REVIEW_CONFIRMED_VIOLATION: {
    get label() { return t('meta.notifyEvent.REVIEW_CONFIRMED_VIOLATION') },
    type: 'danger'
  },
  RECORDING_FAILED: { get label() { return t('meta.notifyEvent.RECORDING_FAILED') }, type: 'warn' },
  REVIEW_BACKLOG: { get label() { return t('meta.notifyEvent.REVIEW_BACKLOG') }, type: 'warn' },
  NOTIFY_TEST: { get label() { return t('meta.notifyEvent.NOTIFY_TEST') }, type: 'info' }
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
  if (sameDay(d, nowDate)) return t('time.todayWithTime', { time: hm })
  const yesterday = new Date(nowDate)
  yesterday.setDate(yesterday.getDate() - 1)
  if (sameDay(d, yesterday)) return t('time.yesterdayWithTime', { time: hm })
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
  return m > 0 ? t('time.minutesSeconds', { m, s }) : t('time.seconds', { s })
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
  if (min < 1) return t('time.justNow')
  if (min < 60) return tn('time.minutesAgo', min, { n: min })
  const hour = Math.floor(min / 60)
  if (hour < 24) return tn('time.hoursAgo', hour, { n: hour })
  const day = Math.floor(hour / 24)
  if (day === 1) {
    const hm = `${String(then.getHours()).padStart(2, '0')}:${String(then.getMinutes()).padStart(2, '0')}`
    return t('time.yesterdayWithTime', { time: hm })
  }
  if (day < 7) return tn('time.daysAgo', day, { n: day })
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
      label: t('meta.reprocess.retry'),
      tone: 'warning',
      confirm: t('meta.reprocess.confirmRetry')
    }
  }
  if (status === 'PENDING') {
    const stale = isStale(row, now)
    return {
      label: stale ? t('meta.reprocess.advance') : t('meta.reprocess.processNow'),
      tone: 'primary',
      confirm: stale ? t('meta.reprocess.confirmAdvance') : t('meta.reprocess.confirmProcessNow')
    }
  }
  if (PROCESSING_STATUSES.includes(status)) {
    if (!isStale(row, now)) return null
    return {
      label: t('meta.reprocess.reprocess'),
      tone: 'warning',
      confirm: t('meta.reprocess.confirmReprocess', {
        status: STATUS_META[status]?.label || status
      })
    }
  }
  return null
}
