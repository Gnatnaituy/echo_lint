/**
 * 异步任务队列归一化 / 状态元数据 / 审计文案单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  AUDIT_ACTION_META,
  AUDIT_ACTION_OPTIONS,
  TASK_STATUS_META,
  TASK_STATUS_OPTIONS,
  auditActionLabel,
  isTaskRetryable,
  taskStatusMeta
} from '../src/constants.js'
import {
  normalizeRetryResult,
  normalizeTask,
  normalizeTaskPage,
  taskAttemptsText
} from '../src/utils/tasks.js'

test('任务：契约字段完整归一化', () => {
  assert.deepEqual(
    normalizeTask({
      id: 7,
      recordingId: 42,
      fileName: 'call-001.mp3',
      status: 'RUNNING',
      attempts: 1,
      maxAttempts: 3,
      nextAttemptAt: '2026-10-20T10:05:00',
      lastError: 'model timeout',
      createdAt: '2026-10-20T10:00:00',
      updatedAt: '2026-10-20T10:01:00',
      finishedAt: '2026-10-20T10:02:00'
    }),
    {
      id: 7,
      recordingId: 42,
      fileName: 'call-001.mp3',
      status: 'RUNNING',
      attempts: 1,
      maxAttempts: 3,
      nextAttemptAt: '2026-10-20T10:05:00',
      lastError: 'model timeout',
      createdAt: '2026-10-20T10:00:00',
      updatedAt: '2026-10-20T10:01:00',
      finishedAt: '2026-10-20T10:02:00'
    }
  )
})

test('任务：兼容 snake_case / 状态大小写 / 错误信息别名', () => {
  const task = normalizeTask({
    taskId: '9',
    recording_id: '12',
    recordingFileName: 'a.wav',
    status: ' dead ',
    retryCount: '2',
    max_attempts: '3',
    next_attempt_at: '2026-10-20 10:05:00',
    errorMessage: 'pipeline failed',
    updated_at: '2026-10-20 10:03:00'
  })
  assert.equal(task.id, 9)
  assert.equal(task.recordingId, 12)
  assert.equal(task.fileName, 'a.wav')
  assert.equal(task.status, 'DEAD')
  assert.equal(task.attempts, 2)
  assert.equal(task.maxAttempts, 3)
  assert.equal(task.nextAttemptAt, '2026-10-20 10:05:00')
  assert.equal(task.lastError, 'pipeline failed')
  assert.equal(task.updatedAt, '2026-10-20 10:03:00')
})

test('任务：null / 非对象 / 缺字段安全兜底（状态记为 UNKNOWN）', () => {
  for (const raw of [null, undefined, 'oops', 42, []]) {
    const task = normalizeTask(raw)
    assert.equal(task.id, null)
    assert.equal(task.recordingId, null)
    assert.equal(task.fileName, '')
    assert.equal(task.status, 'UNKNOWN')
    assert.equal(task.attempts, 0)
    assert.equal(task.maxAttempts, 0)
    assert.equal(task.nextAttemptAt, '')
    assert.equal(task.lastError, '')
    assert.equal(task.updatedAt, '')
  }
})

test('任务分页：Spring Page 形状（content/totalElements/number/size/totalPages）', () => {
  const page = normalizeTaskPage({
    content: [{ id: 1, recordingId: 11, status: 'PENDING' }, { id: 2, recordingId: 12, status: 'FAILED' }],
    totalElements: 12,
    totalPages: 2,
    number: 1,
    size: 10
  })
  assert.equal(page.items.length, 2)
  assert.equal(page.items[1].status, 'FAILED')
  assert.equal(page.total, 12)
  assert.equal(page.page, 1)
  assert.equal(page.size, 10)
  assert.equal(page.totalPages, 2)
})

test('任务分页：裸数组 / items / tasks / data 包装与空响应', () => {
  assert.equal(normalizeTaskPage([{ id: 1 }, { id: 2 }]).total, 2)
  for (const wrapper of [
    { items: [{ id: 1 }] },
    { tasks: [{ id: 1 }] },
    { data: [{ id: 1 }] }
  ]) {
    const page = normalizeTaskPage(wrapper)
    assert.equal(page.items.length, 1)
    assert.equal(page.total, 1)
  }
  assert.deepEqual(normalizeTaskPage(null), {
    items: [],
    total: 0,
    page: 0,
    size: 0,
    totalPages: 0
  })
  assert.deepEqual(normalizeTaskPage('oops').items, [])
})

test('重试结果：任务对象归一化，204 空响应 / 无关对象返回 null', () => {
  const task = normalizeRetryResult({ id: 5, recordingId: 6, status: 'PENDING', attempts: 0 })
  assert.equal(task.id, 5)
  assert.equal(task.status, 'PENDING')
  for (const empty of [undefined, null, '', 42, 'ok', [], {}]) {
    assert.equal(normalizeRetryResult(empty), null)
  }
  // 有内容但不是任务对象（如 {ok:true}）不误判
  assert.equal(normalizeRetryResult({ ok: true }), null)
})

test('尝试次数展示：有最大次数显示 attempts / max，缺失只显示 attempts', () => {
  assert.equal(taskAttemptsText({ attempts: 2, maxAttempts: 3 }), '2 / 3')
  assert.equal(taskAttemptsText({ attempts: '1', maxAttempts: '3' }), '1 / 3')
  assert.equal(taskAttemptsText({ attempts: 2 }), '2')
  assert.equal(taskAttemptsText({}), '0')
  assert.equal(taskAttemptsText(null), '0')
})

test('任务状态元数据：五种状态有中文标签与配色，未知回退原始 code', () => {
  assert.equal(taskStatusMeta('PENDING').label, '排队中')
  assert.equal(taskStatusMeta('RUNNING').label, '执行中')
  assert.equal(taskStatusMeta('SUCCEEDED').label, '已成功')
  assert.equal(taskStatusMeta('FAILED').label, '等待重试')
  assert.equal(taskStatusMeta('DEAD').label, '最终失败')
  assert.equal(taskStatusMeta('FAILED').type, 'warn')
  assert.equal(taskStatusMeta('DEAD').type, 'danger')
  assert.equal(taskStatusMeta('WHATEVER').label, 'WHATEVER')
  assert.equal(taskStatusMeta(null).label, '—')
  assert.deepEqual(
    TASK_STATUS_OPTIONS.map((o) => o.value),
    Object.keys(TASK_STATUS_META)
  )
})

test('任务重试：仅 FAILED / DEAD 可重试', () => {
  assert.equal(isTaskRetryable('FAILED'), true)
  assert.equal(isTaskRetryable('DEAD'), true)
  for (const status of ['PENDING', 'RUNNING', 'SUCCEEDED', 'UNKNOWN', null, undefined]) {
    assert.equal(isTaskRetryable(status), false, String(status))
  }
})

test('审计动作：TASK_RETRY 有中文文案并进入筛选选项', () => {
  assert.equal(auditActionLabel('TASK_RETRY'), '重试任务')
  assert.equal(AUDIT_ACTION_META.TASK_RETRY.type, 'warning')
  assert.ok(AUDIT_ACTION_OPTIONS.some((opt) => opt.value === 'TASK_RETRY'))
})
