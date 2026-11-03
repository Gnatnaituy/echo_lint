/**
 * 日期范围工具（审计 P3-8：跨视图重复实现收敛）。
 *
 * 背景：Costs / Reports / Screen / Recordings 各自维护 `toDateParam` / `dayOffset` /
 * `defaultRange` / `dateShortcuts` / `statsParams` / `rangeText`（语义已轻微分叉），
 * KnowledgeIndex / Tasks 又各有一份 `nowText`。这里统一为纯函数，视图只保留文案（i18n）
 * 与 ref 状态，便于单测与后续扩展。
 *
 * 约定：
 * - `toDateParam` 空值返回 `''`（不返回 undefined，便于 rangeText 拼接）；
 *   传给接口前如需要「未传」语义，调用方用 `|| undefined` 转换。
 * - 非法字符串原样返回（多为用户手输的半成品），非法非字符串返回 `''`。
 * - 所有函数可注入 `base` 日期，单测无需 mock 全局时间。
 */

/** Date -> 本地日期 yyyy-MM-dd；空值 '' ; 非法字符串原样返回；非法其它值 '' */
export function toDateParam(value) {
  if (!value) return ''
  // 只接受 Date / 字符串：避免 `new Date('123')` 被解析成 0123 年这类意外结果
  if (!(value instanceof Date) && typeof value !== 'string') return ''
  const date = value instanceof Date ? value : new Date(String(value).replace(/\//g, '-'))
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 相对 base 平移 days 天（本地零点），快捷选项 / 默认区间共用 */
export function dayOffset(days, base = new Date()) {
  const date = new Date(base)
  date.setHours(0, 0, 0, 0)
  date.setDate(date.getDate() + (Number(days) || 0))
  return date
}

/**
 * 默认区间：最近 N 天（含今天），返回 [yyyy-MM-dd, yyyy-MM-dd]。
 * @param {number} [days=30] 覆盖天数，非正数回退 30
 * @param {Date} [base=new Date()] 基准日期（单测注入）
 */
export function defaultRange(days = 30, base = new Date()) {
  const span = Math.max(1, Math.floor(Number(days)) || 30)
  return [toDateParam(dayOffset(-(span - 1), base)), toDateParam(dayOffset(0, base))]
}

/**
 * el-date-picker 快捷选项（最近 7 / 30 / 90 天）。
 * 文案由调用方传入（各页面 i18n key 不同），保证语言切换时重新求值。
 *
 * @param {{last7Days?: string, last30Days?: string, last90Days?: string}} [labels]
 * @param {Date} [base=new Date()]
 * @returns {{text: string, value: () => Date[]}[]}
 */
export function dateShortcuts(labels = {}, base = new Date()) {
  const presets = [
    { text: labels.last7Days, offset: -6 },
    { text: labels.last30Days, offset: -29 },
    { text: labels.last90Days, offset: -89 }
  ]
  return presets
    .filter((preset) => typeof preset.text === 'string' && preset.text)
    .map((preset) => ({
      text: preset.text,
      value: () => [dayOffset(preset.offset, base), dayOffset(0, base)]
    }))
}

/**
 * 日期区间 -> 统计接口参数：空值转 undefined（后端按「不过滤」处理）。
 * @param {Array<Date|string>|null|undefined} range
 * @returns {{from: string|undefined, to: string|undefined}}
 */
export function statsParams(range) {
  const from = toDateParam(range?.[0])
  const to = toDateParam(range?.[1])
  return { from: from || undefined, to: to || undefined }
}

/**
 * 区间展示文案。
 * @param {Array<Date|string>|null|undefined} range
 * @param {{allTime?: string, earliest?: string, untilNow?: string, compact?: boolean}} [labels]
 *        compact=true 时同年份省略年份（汇总卡副标题空间有限）
 */
export function rangeText(range, labels = {}) {
  const from = toDateParam(range?.[0])
  const to = toDateParam(range?.[1])
  if (!from && !to) return labels.allTime || ''
  if (labels.compact && from && to && from.slice(0, 4) === to.slice(0, 4)) {
    return `${from.slice(5)} ~ ${to.slice(5)}`
  }
  return `${from || labels.earliest || ''} ~ ${to || labels.untilNow || ''}`
}

/** 当前本地时间 HH:mm:ss（数据刷新时间戳） */
export function nowText(now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}
