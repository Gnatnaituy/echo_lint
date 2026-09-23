/**
 * 手动推进/重新处理按钮逻辑单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isStale, reprocessInfo } from '../src/constants.js'

// 后端返回的 LocalDateTime 无时区（如 2026-09-22T11:30:00），浏览器按本地时间解析
const NOW = new Date(2026, 8, 22, 12, 0, 0).getTime() // 本地时间 2026-09-22 12:00
const ago = (minutes) => {
  const d = new Date(NOW - minutes * 60 * 1000)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

test('PENDING 始终可手动推进', () => {
  const fresh = reprocessInfo({ status: 'PENDING', statusUpdatedAt: ago(0.2) }, NOW)
  assert.equal(fresh.label, '立即处理')
  const stale = reprocessInfo({ status: 'PENDING', statusUpdatedAt: ago(30) }, NOW)
  assert.equal(stale.label, '推进处理')
  assert.match(stale.confirm, /服务重启/)
})

test('FAILED 给出重试', () => {
  const info = reprocessInfo({ status: 'FAILED', statusUpdatedAt: ago(5) }, NOW)
  assert.equal(info.label, '重试')
})

test('进行中：未卡住不给按钮，卡住才给重新处理', () => {
  assert.equal(reprocessInfo({ status: 'TRANSCRIBING', statusUpdatedAt: ago(0.5) }, NOW), null)
  assert.equal(reprocessInfo({ status: 'DFA_CHECKING', statusUpdatedAt: ago(1) }, NOW), null)
  const stuck = reprocessInfo({ status: 'AI_CHECKING', statusUpdatedAt: ago(10) }, NOW)
  assert.equal(stuck.label, '重新处理')
  assert.match(stuck.confirm, /疑似中断/)
})

test('已有结论的状态不给重新处理按钮', () => {
  for (const status of ['COMPLIANT', 'NEEDS_REVIEW', 'VIOLATION_CONFIRMED', 'FALSE_POSITIVE']) {
    assert.equal(reprocessInfo({ status, statusUpdatedAt: ago(60) }, NOW), null, status)
  }
})

test('isStale 仅在中间态且超时才算卡住', () => {
  assert.equal(isStale({ status: 'PENDING', statusUpdatedAt: ago(3) }, NOW), true)
  assert.equal(isStale({ status: 'PENDING', statusUpdatedAt: ago(1) }, NOW), false)
  assert.equal(isStale({ status: 'COMPLIANT', statusUpdatedAt: ago(999) }, NOW), false)
  // 缺少状态时间时退回 uploadTime
  assert.equal(isStale({ status: 'PENDING', uploadTime: ago(5) }, NOW), true)
  // 没有时间信息则不判定为卡住
  assert.equal(isStale({ status: 'PENDING' }, NOW), false)
})
