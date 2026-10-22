/**
 * 人工复检队列分页的纯逻辑（无组件依赖，便于 node:test 单测）。
 *
 * 复检队列走服务端分页：页码 0 基、每页 {@link QUEUE_PAGE_SIZE} 条，
 * 关键词 / 优先级 / 指派筛选也交给后端，保证「共 N 条」与翻页结果一致，
 * 不会出现「只过滤当前页」的错觉。
 */

/** 队列每页条数：左侧队列是窄栏，10 条一页翻页成本低，也与「已复检」分页一致 */
export const QUEUE_PAGE_SIZE = 10

function nonNegativeInt(value, fallback = 0) {
  const num = Number(value)
  if (!Number.isFinite(num) || num < 0) return fallback
  return Math.floor(num)
}

function positiveInt(value, fallback = 1) {
  const num = Number(value)
  if (!Number.isFinite(num) || num < 1) return fallback
  return Math.floor(num)
}

/** 队列里的全局序号（跨页连续）：第 0 页第 0 条 = 1 */
export function queueGlobalIndex(page, pageSize, index) {
  return nonNegativeInt(page) * positiveInt(pageSize) + nonNegativeInt(index) + 1
}

/** 复核/移走当前页最后一条后，应回退到的页码（0 基）；本页还有数据则停留 */
export function queuePageAfterRemoval({ page, pageSize, totalAfter } = {}) {
  const current = nonNegativeInt(page)
  const size = positiveInt(pageSize)
  const total = nonNegativeInt(totalAfter)
  if (current > 0 && current * size >= total) return current - 1
  return current
}

/** 总页数（空队列为 0，与「下一条」的翻页判断一致） */
export function queueTotalPages(total, pageSize) {
  const size = positiveInt(pageSize)
  return Math.ceil(nonNegativeInt(total) / size)
}

/** 「下一条（剩 N）」里的剩余条数：不含当前这条 */
export function queueRemaining(total) {
  return Math.max(0, nonNegativeInt(total) - 1)
}

/** 是否有队列筛选条件：用于空态文案区分「没有匹配」与「队列已清空」 */
export function queueHasFilter({ keyword, priority, assignFilter } = {}) {
  return Boolean((keyword || '').trim() || priority || assignFilter)
}

/**
 * GET /api/recordings 参数：只查待复检 + 服务端筛选 + 分页。
 * assignFilter=unassigned 时忽略 assignedTo，改用 unassigned=true（后端按 assignedTo is null 过滤）。
 */
export function queueQuery({ keyword, priority, assignFilter, username, page, size } = {}) {
  const params = {
    statuses: ['NEEDS_REVIEW'],
    keyword: (keyword || '').trim() || undefined,
    priority: priority || undefined,
    page: nonNegativeInt(page),
    size: positiveInt(size, QUEUE_PAGE_SIZE)
  }
  if (assignFilter === 'mine' && username) {
    params.assignedTo = username
  } else if (assignFilter === 'unassigned') {
    params.unassigned = true
  }
  return params
}
