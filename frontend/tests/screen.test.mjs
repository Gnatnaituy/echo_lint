/**
 * 数据大屏归一化 / 格式化单测
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  formatCount,
  formatRate,
  normalizeScreenMetrics,
  normalizeScreenTrend,
  normalizeTopRows,
  normalizeViolationDistribution
} from '../src/utils/screen.js'

test('大屏 KPI：总量 / 今日 / 待复检 / 违规率', () => {
  const metrics = normalizeScreenMetrics(
    {
      totalUploads: 1200,
      todayUploads: 34,
      statusCounts: { NEEDS_REVIEW: 12, COMPLIANT: 1188 }
    },
    { byStatus: { VIOLATION_CONFIRMED: 60 } }
  )
  assert.equal(metrics.total, 1200)
  assert.equal(metrics.today, 34)
  assert.equal(metrics.needsReview, 12)
  assert.equal(metrics.confirmed, 60)
  assert.equal(metrics.violationRate, 60 / 1200)
})

test('大屏 KPI：overview 缺失时回退 report.total，除零时违规率为 null', () => {
  assert.equal(normalizeScreenMetrics(null, { total: 88 }).total, 88)
  const zero = normalizeScreenMetrics({ totalUploads: 0 }, { byStatus: { VIOLATION_CONFIRMED: 0 } })
  assert.equal(zero.violationRate, null)
  // needsReview 回退 report.byStatus
  const fallback = normalizeScreenMetrics(null, { total: 10, byStatus: { NEEDS_REVIEW: 3 } })
  assert.equal(fallback.needsReview, 3)
  // 脏数据安全兜底
  const dirty = normalizeScreenMetrics('oops', 'bad')
  assert.deepEqual(dirty, { total: 0, today: 0, needsReview: 0, confirmed: 0, violationRate: null })
})

test('近 7 日趋势：字段别名 / 过滤空标签 / 非数组', () => {
  const trend = normalizeScreenTrend({
    last7Days: [
      { label: '10-01', count: 3 },
      { date: '10-02', total: '5' },
      { day: '10-03', uploads: 2 },
      { label: '', count: 9 }
    ]
  })
  assert.deepEqual(trend, [
    { label: '10-01', count: 3 },
    { label: '10-02', count: 5 },
    { label: '10-03', count: 2 }
  ])
  assert.deepEqual(normalizeScreenTrend({}), [])
  assert.deepEqual(normalizeScreenTrend(null), [])
})

test('违规类型分布：数组/对象 map、排序、占比、limit、全零过滤', () => {
  const rows = normalizeViolationDistribution({
    byViolationType: [
      { type: 'INSULT', label: '辱骂', count: 2 },
      { type: 'THREAT', label: '威胁', count: 6 },
      { type: 'OTHER', count: 0 }
    ]
  })
  assert.deepEqual(rows.map((r) => r.key), ['THREAT', 'INSULT'])
  assert.equal(rows[0].count, 6)
  assert.equal(rows[0].percent, 0.75)
  assert.equal(rows[1].percent, 0.25)
  assert.equal(normalizeViolationDistribution({ byViolationType: [] }).length, 0)

  const limited = normalizeViolationDistribution(
    { byViolationType: [{ label: 'A', count: 1 }, { label: 'B', count: 3 }] },
    1
  )
  assert.deepEqual(limited.map((r) => r.label), ['B'])

  const map = normalizeViolationDistribution({ violationTypes: { INSULT: 1, THREAT: 3 } })
  assert.deepEqual(map.map((r) => r.key), ['THREAT', 'INSULT'])
})

test('Top 坐席/团队：排序、limit、零值过滤、空值名兜底', () => {
  const rows = normalizeTopRows(
    [
      { name: 'A', count: 3 },
      { agentName: 'B', total: 8 },
      { name: '', count: 2 },
      { name: 'C', count: 0 }
    ],
    2
  )
  assert.equal(rows.length, 2)
  assert.equal(rows[0].name, 'B')
  assert.equal(rows[0].count, 8)
  assert.equal(rows[1].name, 'A')
  assert.deepEqual(normalizeTopRows(null), [])
})

test('格式化：千分位与百分比（无效显示占位）', () => {
  assert.equal(formatCount(1234567), '1,234,567')
  assert.equal(formatCount(0), '0')
  assert.equal(formatCount('x'), '0')
  assert.equal(formatRate(0.5), '50%')
  assert.equal(formatRate(0.8333), '83.3%')
  assert.equal(formatRate(null), '—')
  assert.equal(formatRate(-1), '—')
})
