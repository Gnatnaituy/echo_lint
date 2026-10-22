/**
 * 人工复检队列分页纯逻辑单测：全局序号、翻页回退、总页数、剩余条数、筛选条件与请求参数。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  QUEUE_PAGE_SIZE,
  queueGlobalIndex,
  queueHasFilter,
  queuePageAfterRemoval,
  queueQuery,
  queueRemaining,
  queueTotalPages
} from '../src/utils/reviewQueue.js'

test('队列每页 10 条', () => {
  assert.equal(QUEUE_PAGE_SIZE, 10)
})

test('全局序号跨页连续', () => {
  assert.equal(queueGlobalIndex(0, 10, 0), 1)
  assert.equal(queueGlobalIndex(0, 10, 9), 10)
  assert.equal(queueGlobalIndex(1, 10, 0), 11)
  assert.equal(queueGlobalIndex(2, 10, 4), 25)
  // 非法输入安全兜底
  assert.equal(queueGlobalIndex(-1, 10, -3), 1)
  assert.equal(queueGlobalIndex('1', '10', '2'), 13)
  assert.equal(queueGlobalIndex(1, 0, 0), 2)
})

test('复核掉本页最后一条后回退一页，否则停留', () => {
  // 第 2 页只有 1 条（total=11），复核后本页为空 → 回退到第 1 页
  assert.equal(queuePageAfterRemoval({ page: 1, pageSize: 10, totalAfter: 10 }), 0)
  // 第 2 页还有 2 条 → 停留
  assert.equal(queuePageAfterRemoval({ page: 1, pageSize: 10, totalAfter: 12 }), 1)
  // 首页永不为负
  assert.equal(queuePageAfterRemoval({ page: 0, pageSize: 10, totalAfter: 0 }), 0)
  // 空队列/非法入参
  assert.equal(queuePageAfterRemoval(), 0)
  assert.equal(queuePageAfterRemoval({ page: 3, pageSize: 10, totalAfter: 0 }), 2)
})

test('总页数与剩余条数', () => {
  assert.equal(queueTotalPages(0, 10), 0)
  assert.equal(queueTotalPages(1, 10), 1)
  assert.equal(queueTotalPages(10, 10), 1)
  assert.equal(queueTotalPages(11, 10), 2)
  assert.equal(queueTotalPages(100, 10), 10)
  assert.equal(queueTotalPages('25', 10), 3)

  assert.equal(queueRemaining(0), 0)
  assert.equal(queueRemaining(1), 0)
  assert.equal(queueRemaining(9), 8)
  assert.equal(queueRemaining(-2), 0)
})

test('有筛选条件时用于「没有匹配」空态文案', () => {
  assert.equal(queueHasFilter({}), false)
  assert.equal(queueHasFilter({ keyword: '   ', priority: '', assignFilter: '' }), false)
  assert.equal(queueHasFilter({ keyword: '退款' }), true)
  assert.equal(queueHasFilter({ priority: 'HIGH' }), true)
  assert.equal(queueHasFilter({ assignFilter: 'unassigned' }), true)
})

test('队列请求参数：只查待复检，筛选与分页走服务端', () => {
  assert.deepEqual(queueQuery({}), {
    statuses: ['NEEDS_REVIEW'],
    keyword: undefined,
    priority: undefined,
    page: 0,
    size: 10
  })

  assert.deepEqual(
    queueQuery({
      keyword: ' 退款 ',
      priority: 'HIGH',
      assignFilter: 'unassigned',
      username: 'alice',
      page: 1,
      size: 10
    }),
    {
      statuses: ['NEEDS_REVIEW'],
      keyword: '退款',
      priority: 'HIGH',
      page: 1,
      size: 10,
      unassigned: true
    }
  )

  // 「只看我的」用 assignedTo 精确过滤，不再带 unassigned
  const mine = queueQuery({ assignFilter: 'mine', username: 'alice' })
  assert.equal(mine.assignedTo, 'alice')
  assert.equal(mine.unassigned, undefined)

  // 没有用户名时不做指派过滤，避免传空串被当成有效条件
  const anonymous = queueQuery({ assignFilter: 'mine', username: '' })
  assert.equal(anonymous.assignedTo, undefined)
  assert.equal(anonymous.unassigned, undefined)
})
