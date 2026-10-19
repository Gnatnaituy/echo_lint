/**
 * 批量上传队列纯逻辑（Batch 4b）：不依赖 Vue / DOM，便于 node --test 单测。
 *
 * 队列项：{uid, file, name, size, progress(0~100), status, error}
 * 状态：pending -> uploading -> success | error；未开始的可被取消为 canceled。
 */

export const UPLOAD_STATUS = {
  PENDING: 'pending',
  UPLOADING: 'uploading',
  SUCCESS: 'success',
  ERROR: 'error',
  CANCELED: 'canceled'
}

/** 由 File 列表创建队列（uid 稳定，便于 UI key） */
export function createUploadQueue(files) {
  const list = Array.isArray(files) ? files : []
  return list.map((file, index) => ({
    uid: `u-${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`,
    file,
    name: file?.name || `文件${index + 1}`,
    size: Number(file?.size) || 0,
    progress: 0,
    status: UPLOAD_STATUS.PENDING,
    error: ''
  }))
}

/** 不可变更新单个队列项 */
export function updateQueueItem(queue, uid, patch) {
  return (queue || []).map((item) => (item.uid === uid ? { ...item, ...patch } : item))
}

/** 下一个待上传项索引；无则 -1 */
export function nextPendingIndex(queue) {
  return (queue || []).findIndex((item) => item.status === UPLOAD_STATUS.PENDING)
}

/** 取消剩余：把所有 pending 标为 canceled（不影响已完成 / 上传中） */
export function markRemainingCanceled(queue) {
  return (queue || []).map((item) =>
    item.status === UPLOAD_STATUS.PENDING
      ? { ...item, status: UPLOAD_STATUS.CANCELED, error: '已取消' }
      : item
  )
}

/** 汇总：成功 / 失败 / 取消 / 等待中，以及完成百分比 */
export function summarizeQueue(queue) {
  const list = Array.isArray(queue) ? queue : []
  const summary = {
    total: list.length,
    pending: 0,
    uploading: 0,
    success: 0,
    error: 0,
    canceled: 0
  }
  for (const item of list) {
    if (item.status === UPLOAD_STATUS.PENDING) summary.pending += 1
    else if (item.status === UPLOAD_STATUS.UPLOADING) summary.uploading += 1
    else if (item.status === UPLOAD_STATUS.SUCCESS) summary.success += 1
    else if (item.status === UPLOAD_STATUS.ERROR) summary.error += 1
    else if (item.status === UPLOAD_STATUS.CANCELED) summary.canceled += 1
  }
  const done = summary.success + summary.error + summary.canceled
  summary.done = done
  summary.percent = summary.total > 0 ? Math.round((done / summary.total) * 100) : 0
  return summary
}

/** 整体进度：按文件大小加权；大小未知时退化为进度平均值 */
export function overallProgress(queue) {
  const list = Array.isArray(queue) ? queue : []
  if (!list.length) return 0
  const totalSize = list.reduce((sum, item) => sum + (Number(item.size) || 0), 0)
  if (totalSize <= 0) {
    const avg = list.reduce((sum, item) => sum + clampProgress(item.progress), 0) / list.length
    return Math.round(avg)
  }
  const loaded = list.reduce(
    (sum, item) => sum + (Number(item.size) || 0) * (clampProgress(item.progress) / 100),
    0
  )
  return Math.round((loaded / totalSize) * 100)
}

/** axios onUploadProgress 事件 -> 0~100 整数 */
export function progressFromEvent(event) {
  const loaded = Number(event?.loaded)
  const total = Number(event?.total)
  if (!Number.isFinite(loaded) || !Number.isFinite(total) || total <= 0) return 0
  return clampProgress(Math.round((loaded / total) * 100))
}

/** 是否全部结束（成功 / 失败 / 取消） */
export function isUploadFinished(queue) {
  const list = Array.isArray(queue) ? queue : []
  return (
    list.length > 0 &&
    list.every((item) =>
      [UPLOAD_STATUS.SUCCESS, UPLOAD_STATUS.ERROR, UPLOAD_STATUS.CANCELED].includes(item.status)
    )
  )
}

export const UPLOAD_STATUS_LABEL = {
  pending: '等待上传',
  uploading: '上传中',
  success: '上传成功',
  error: '上传失败',
  canceled: '已取消'
}

function clampProgress(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return 0
  return Math.min(100, Math.max(0, num))
}
