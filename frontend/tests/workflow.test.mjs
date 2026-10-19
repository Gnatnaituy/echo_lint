/**
 * 身份 / 复核工作流 / 操作审计 纯函数单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ROLE_META,
  ROLE_OPTIONS,
  roleLabel,
  PRIORITY_META,
  PRIORITY_OPTIONS,
  priorityMeta,
  FINAL_REVIEW_STATUSES,
  isFinalReviewStatus,
  reviewProgress,
  AUDIT_ACTION_META,
  AUDIT_ACTION_OPTIONS,
  auditActionMeta,
  auditActionLabel,
  formatSmartTime,
  safeRedirect
} from '../src/constants.js'

test('角色：三种角色都有中文标签，未知角色回退原文', () => {
  assert.equal(roleLabel('ADMIN'), '管理员')
  assert.equal(roleLabel('REVIEWER'), '复核员')
  assert.equal(roleLabel('VIEWER'), '只读')
  assert.equal(roleLabel('AUDITOR'), 'AUDITOR')
  assert.equal(roleLabel(null), '—')
  assert.deepEqual(
    ROLE_OPTIONS.map((o) => o.value),
    Object.keys(ROLE_META)
  )
})

test('优先级：未知/空回退 NORMAL，选项按 高→普通→低 排序', () => {
  assert.equal(priorityMeta('HIGH').label, '高')
  assert.equal(priorityMeta('NORMAL').label, '普通')
  assert.equal(priorityMeta('LOW').label, '低')
  assert.equal(priorityMeta(undefined).label, '普通')
  assert.equal(priorityMeta('URGENT').label, '普通')
  assert.deepEqual(
    PRIORITY_OPTIONS.map((o) => o.value),
    ['HIGH', 'NORMAL', 'LOW']
  )
  assert.equal(PRIORITY_META.HIGH.type, 'danger')
})

test('复核进度：默认单人复核，双人复核按已提交人数展示', () => {
  const single = reviewProgress(null)
  assert.deepEqual(single, { count: 0, required: 1, done: false, remaining: 1, text: '0/1' })

  const first = reviewProgress({ reviewCount: 1, requiredApprovals: 2 })
  assert.deepEqual(first, { count: 1, required: 2, done: false, remaining: 1, text: '1/2' })

  const done = reviewProgress({ reviewCount: 2, requiredApprovals: 2 })
  assert.equal(done.done, true)
  assert.equal(done.remaining, 0)

  // 缺字段 / 字符串数字 / 负值都兜底
  assert.equal(reviewProgress({ reviewCount: '1' }).text, '1/1')
  assert.equal(reviewProgress({ reviewCount: -3 }).count, 0)
  assert.equal(reviewProgress({ requiredApprovals: 0 }).required, 1)
})

test('复核终态：仅最终结论允许 ADMIN 重开', () => {
  assert.deepEqual(FINAL_REVIEW_STATUSES.sort(), ['COMPLIANT', 'FALSE_POSITIVE', 'VIOLATION_CONFIRMED'])
  assert.equal(isFinalReviewStatus('COMPLIANT'), true)
  assert.equal(isFinalReviewStatus('VIOLATION_CONFIRMED'), true)
  assert.equal(isFinalReviewStatus('FALSE_POSITIVE'), true)
  assert.equal(isFinalReviewStatus('NEEDS_REVIEW'), false)
  assert.equal(isFinalReviewStatus('FAILED'), false)
  assert.equal(isFinalReviewStatus(null), false)
})

test('审计动作：已知动作有中文标签，未知动作回退原始 code', () => {
  assert.equal(auditActionLabel('LOGIN'), '登录')
  assert.equal(auditActionLabel('REVIEW_SUBMIT'), '提交复核')
  assert.equal(auditActionLabel('RECORDING_CLAIM'), '认领录音')
  assert.equal(auditActionLabel('SOMETHING_NEW'), 'SOMETHING_NEW')
  assert.equal(auditActionMeta(undefined).label, '—')
  assert.ok(AUDIT_ACTION_OPTIONS.length >= Object.keys(AUDIT_ACTION_META).length)
})

test('智能时间：今天 / 昨天 / 今年 / 更早 + 异常输入', () => {
  // 本地时间 2026-10-20 15:30
  const NOW = new Date(2026, 9, 20, 15, 30, 0).getTime()
  assert.equal(formatSmartTime('2026-10-20T09:05:00', NOW), '今天 09:05')
  assert.equal(formatSmartTime('2026-10-20 23:59:00', NOW), '今天 23:59')
  assert.equal(formatSmartTime('2026-10-19T23:59:00', NOW), '昨天 23:59')
  assert.equal(formatSmartTime('2026-03-01T08:00:00', NOW), '03-01 08:00')
  assert.equal(formatSmartTime('2025-12-31T23:00:00', NOW), '2025-12-31 23:00')
  assert.equal(formatSmartTime(null, NOW), '—')
  assert.equal(formatSmartTime('', NOW), '—')
  assert.equal(formatSmartTime('not-a-date', NOW), 'not-a-date')
})

test('登录后跳转：只接受站内路径，拒绝开放重定向', () => {
  assert.equal(safeRedirect('/recordings?statuses=FAILED'), '/recordings?statuses=FAILED')
  assert.equal(safeRedirect('/review?id=12'), '/review?id=12')
  assert.equal(safeRedirect('https://evil.example.com'), '/')
  assert.equal(safeRedirect('//evil.example.com'), '/')
  assert.equal(safeRedirect('/login?redirect=/x'), '/')
  assert.equal(safeRedirect(''), '/')
  assert.equal(safeRedirect(undefined), '/')
  assert.equal(safeRedirect(['/x']), '/')
  assert.equal(safeRedirect('/x', '/review'), '/x')
  assert.equal(safeRedirect('', '/review'), '/review')
})
