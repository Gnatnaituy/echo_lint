/**
 * 响应式 / 无障碍纯逻辑单测
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  NARROW_BREAKPOINT,
  isEscapeKey,
  isNarrowWidth,
  nextFocusIndex,
  resolveDrawerOpen,
  shouldCloseDrawerOnKey
} from '../src/utils/ui.js'

test('断点判断：<= 900px 视为窄屏，非法输入安全兜底', () => {
  assert.equal(NARROW_BREAKPOINT, 900)
  assert.equal(isNarrowWidth(900), true)
  assert.equal(isNarrowWidth(899.5), true)
  assert.equal(isNarrowWidth(901), false)
  assert.equal(isNarrowWidth('768'), true)
  assert.equal(isNarrowWidth('abc'), false)
  assert.equal(isNarrowWidth(undefined), false)
  assert.equal(isNarrowWidth(1000, 1200), true)
})

test('ESC 识别：key / code / keyCode 三种来源', () => {
  assert.equal(isEscapeKey({ key: 'Escape' }), true)
  assert.equal(isEscapeKey({ code: 'Escape' }), true)
  assert.equal(isEscapeKey({ keyCode: 27 }), true)
  assert.equal(isEscapeKey({ key: 'Enter' }), false)
  assert.equal(isEscapeKey(null), false)
})

test('抽屉状态：仅窄屏允许打开，宽屏强制关闭', () => {
  assert.equal(resolveDrawerOpen({ narrow: true, requested: true }), true)
  assert.equal(resolveDrawerOpen({ narrow: true, requested: false }), false)
  assert.equal(resolveDrawerOpen({ narrow: false, requested: true }), false)
})

test('ESC 仅在窄屏且抽屉打开时关闭', () => {
  assert.equal(shouldCloseDrawerOnKey({ narrow: true, open: true, key: 'Escape' }), true)
  assert.equal(shouldCloseDrawerOnKey({ narrow: true, open: true, key: 'Enter' }), false)
  assert.equal(shouldCloseDrawerOnKey({ narrow: false, open: true, key: 'Escape' }), false)
  assert.equal(shouldCloseDrawerOnKey({ narrow: true, open: false, key: 'Escape' }), false)
})

test('焦点循环索引：向前/向后回绕，空列表返回 -1', () => {
  assert.equal(nextFocusIndex(0, 3), 1)
  assert.equal(nextFocusIndex(2, 3), 0)
  assert.equal(nextFocusIndex(0, 3, -1), 2)
  assert.equal(nextFocusIndex(-1, 3), 0)
  assert.equal(nextFocusIndex(0, 0), -1)
  assert.equal(nextFocusIndex(0, 'x'), -1)
})
