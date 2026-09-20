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
