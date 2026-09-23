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
