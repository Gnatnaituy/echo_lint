/**
 * 跨视图日期工具单测（审计 P3-8）：空值约定 / 日期偏移 / 快捷选项 / 区间文案。
 *
 * 全部注入固定 base 日期，不依赖运行时刻（避免跨天 / 时区导致 flaky）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  dateShortcuts,
  dayOffset,
  defaultRange,
  nowText,
  rangeText,
  statsParams,
  toDateParam
} from '../src/utils/dateRange.js'

/** 固定基准：2026-11-03 15:30 本地时间 */
const BASE = new Date(2026, 10, 3, 15, 30, 0)

test('toDateParam：空值返回空串，Date / 斜杠字符串 / ISO 统一 yyyy-MM-dd', () => {
  assert.equal(toDateParam(null), '')
  assert.equal(toDateParam(undefined), '')
  assert.equal(toDateParam(''), '')
  assert.equal(toDateParam(0), '')
  assert.equal(toDateParam('2026/11/3'), '2026-11-03')
  assert.equal(toDateParam('2026-11-03T09:30:00'), '2026-11-03')
  assert.equal(toDateParam(new Date(2026, 10, 3, 23, 59)), '2026-11-03')
  assert.equal(toDateParam(new Date(2026, 0, 5)), '2026-01-05')
})

test('toDateParam：非法字符串原样返回，非法非字符串返回空串（不臆造日期）', () => {
  assert.equal(toDateParam('2026-13-99'), '2026-13-99')
  assert.equal(toDateParam('not-a-date'), 'not-a-date')
  assert.equal(toDateParam({}), '')
  assert.equal(toDateParam(123), '')
})

test('dayOffset：本地零点偏移，跨月 / 跨年正确，非法天数回退基准日', () => {
  const prev = dayOffset(-1, BASE)
  assert.equal(prev.getDate(), 2)
  assert.equal(prev.getMonth(), 10)
  assert.equal(prev.getHours(), 0)
  assert.equal(prev.getMinutes(), 0)
  assert.equal(toDateParam(dayOffset(0, BASE)), '2026-11-03')
  assert.equal(toDateParam(dayOffset(-29, BASE)), '2026-10-05')
  // 跨年
  assert.equal(toDateParam(dayOffset(-3, new Date(2027, 0, 1))), '2026-12-29')
  // 非法 / 缺失天数按 0 处理，不产生 Invalid Date
  assert.equal(toDateParam(dayOffset('oops', BASE)), '2026-11-03')
  assert.equal(toDateParam(dayOffset(undefined, BASE)), '2026-11-03')
})

test('defaultRange：默认 30 天（含今天），可指定天数，非法天数回退 30', () => {
  assert.deepEqual(defaultRange(30, BASE), ['2026-10-05', '2026-11-03'])
  assert.deepEqual(defaultRange(7, BASE), ['2026-10-28', '2026-11-03'])
  assert.deepEqual(defaultRange(1, BASE), ['2026-11-03', '2026-11-03'])
  // 非正数 / NaN 回退默认 30 天（不能返回空区间）
  assert.deepEqual(defaultRange(0, BASE), ['2026-10-05', '2026-11-03'])
  assert.deepEqual(defaultRange('bad', BASE), ['2026-10-05', '2026-11-03'])
})

test('dateShortcuts：7 / 30 / 90 天快捷选项，文案可缺省过滤，value 以 base 计算', () => {
  const shortcuts = dateShortcuts(
    { last7Days: '近 7 天', last30Days: '近 30 天', last90Days: '近 90 天' },
    BASE
  )
  assert.deepEqual(
    shortcuts.map((item) => item.text),
    ['近 7 天', '近 30 天', '近 90 天']
  )
  const ranges = shortcuts.map((item) => item.value().map(toDateParam))
  assert.deepEqual(ranges, [
    ['2026-10-28', '2026-11-03'],
    ['2026-10-05', '2026-11-03'],
    ['2026-08-06', '2026-11-03']
  ])
  // 每次调用返回新数组（Element Plus 会直接使用该区间，不能共享引用）
  assert.notEqual(shortcuts[0].value(), shortcuts[0].value())
  // 缺文案的项被过滤，避免出现空文本按钮
  assert.deepEqual(dateShortcuts({ last7Days: '近 7 天' }, BASE).length, 1)
  assert.deepEqual(dateShortcuts({}, BASE), [])
})

test('statsParams：空区间 -> undefined（不传筛选），有值 -> yyyy-MM-dd', () => {
  assert.deepEqual(statsParams(null), { from: undefined, to: undefined })
  assert.deepEqual(statsParams([]), { from: undefined, to: undefined })
  assert.deepEqual(statsParams([null, null]), { from: undefined, to: undefined })
  assert.deepEqual(statsParams([new Date(2026, 9, 5), new Date(2026, 10, 3)]), {
    from: '2026-10-05',
    to: '2026-11-03'
  })
  // 只有一端：另一端保持「未传」
  assert.deepEqual(statsParams([dayOffset(-29, BASE), null]), {
    from: '2026-10-05',
    to: undefined
  })
})

test('rangeText：空区间用 allTime，同年 compact 省略年份，跨年保留完整日期', () => {
  const labels = { allTime: '全部时间', earliest: '最早', untilNow: '至今' }
  assert.equal(rangeText(null, labels), '全部时间')
  assert.equal(rangeText([null, null], labels), '全部时间')
  assert.equal(
    rangeText([new Date(2026, 9, 5), new Date(2026, 10, 3)], labels),
    '2026-10-05 ~ 2026-11-03'
  )
  // compact：同年份只保留 MM-DD
  assert.equal(
    rangeText([new Date(2026, 9, 5), new Date(2026, 10, 3)], { ...labels, compact: true }),
    '10-05 ~ 11-03'
  )
  // compact 但跨年：保留完整日期
  assert.equal(
    rangeText([new Date(2025, 11, 20), new Date(2026, 0, 5)], { ...labels, compact: true }),
    '2025-12-20 ~ 2026-01-05'
  )
  // 缺一端：用 earliest / untilNow 兜底
  assert.equal(rangeText([new Date(2026, 9, 5), null], labels), '2026-10-05 ~ 至今')
  assert.equal(rangeText([null, new Date(2026, 10, 3)], labels), '最早 ~ 2026-11-03')
})

test('nowText：HH:mm:ss 补零', () => {
  assert.equal(nowText(new Date(2026, 10, 3, 9, 5, 7)), '09:05:07')
  assert.equal(nowText(new Date(2026, 10, 3, 23, 59, 59)), '23:59:59')
})
