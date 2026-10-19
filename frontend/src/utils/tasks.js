/**
 * 异步任务（pipeline_tasks）响应归一化。
 *
 * 后端契约（docs/production-readiness.md Batch 3b-1）：
 * - GET  /api/tasks?status=&recordingId=&page=&size= -> Spring Page
 *        {content:[{id,recordingId,fileName,status,attempts,maxAttempts,nextAttemptAt,lastError,
 *                   createdAt,updatedAt,finishedAt}], totalElements, totalPages, number, size}
 * - POST /api/tasks/{id}/retry -> 任务对象或 204（空响应）
 *
 * 所有字段做防御性归一化：缺字段 -> null / 0 / ''，状态缺失记为 UNKNOWN，
 * 保证页面在旧后端或半上线状态下不崩溃、不臆造状态。
 */

import { asObject, pick, toNumber, toText } from './shape.js'

/** 单条任务：兼容 camelCase / snake_case 与常见重命名 */
export function normalizeTask(raw) {
  const data = asObject(raw)
  return {
    id: toNumber(pick(data, ['id', 'taskId'])),
    recordingId: toNumber(pick(data, ['recordingId', 'recording_id'])),
    fileName: toText(pick(data, ['fileName', 'recordingFileName', 'recordingName'])),
    status: toText(pick(data, ['status'])).toUpperCase() || 'UNKNOWN',
    attempts: toNumber(pick(data, ['attempts', 'retryCount', 'attempt'])) ?? 0,
    maxAttempts: toNumber(pick(data, ['maxAttempts', 'max_attempts', 'maxRetries'])) ?? 0,
    nextAttemptAt: toText(pick(data, ['nextAttemptAt', 'next_attempt_at'])),
    lastError: toText(pick(data, ['lastError', 'last_error', 'errorMessage', 'message'])),
    createdAt: toText(pick(data, ['createdAt', 'created_at'])),
    updatedAt: toText(pick(data, ['updatedAt', 'updated_at'])),
    finishedAt: toText(pick(data, ['finishedAt', 'finished_at']))
  }
}

/**
 * Spring Page 归一化：兼容裸数组 / {content|items|tasks|data: []} 包装。
 * page 保持后端语义（Spring 的 number 为 0 基），由页面负责 +1 展示。
 */
export function normalizeTaskPage(raw) {
  if (Array.isArray(raw)) {
    const items = raw.map(normalizeTask)
    return { items, total: items.length, page: 0, size: items.length, totalPages: 0 }
  }
  const data = asObject(raw)
  let source = []
  for (const key of ['content', 'items', 'tasks', 'data']) {
    if (Array.isArray(data[key])) {
      source = data[key]
      break
    }
  }
  const items = source.map(normalizeTask)
  return {
    items,
    total: toNumber(pick(data, ['totalElements', 'total', 'totalCount'])) ?? items.length,
    page: toNumber(pick(data, ['number', 'page', 'pageNumber', 'current'])) ?? 0,
    size: toNumber(pick(data, ['size', 'pageSize'])) ?? items.length,
    totalPages: toNumber(pick(data, ['totalPages', 'pages'])) ?? 0
  }
}

/**
 * 重试结果：后端可能返回任务对象，也可能 204 空响应（axios 拿到 undefined/空串）。
 * 返回归一化任务或 null（调用方统一重新拉取列表）。
 */
export function normalizeRetryResult(raw) {
  if (raw === undefined || raw === null || raw === '') return null
  if (typeof raw !== 'object' || Array.isArray(raw)) return null
  const data = asObject(raw)
  const hasTaskShape = pick(data, ['id', 'taskId', 'status', 'recordingId']) !== undefined
  return hasTaskShape ? normalizeTask(data) : null
}

/** 尝试次数展示：maxAttempts 缺失时只显示 attempts */
export function taskAttemptsText(task) {
  const attempts = toNumber(task?.attempts) ?? 0
  const maxAttempts = toNumber(task?.maxAttempts) ?? 0
  return maxAttempts > 0 ? `${attempts} / ${maxAttempts}` : `${attempts}`
}
