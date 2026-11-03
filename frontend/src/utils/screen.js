/**
 * 数据大屏（/screen）数据归一化与格式化。
 *
 * 数据来源：
 * - GET /api/stats/overview -> totalUploads / todayUploads / statusCounts / last7Days[{label,count}]
 * - GET /api/stats/report   -> total / byStatus / byViolationType[] / topAgents[] / topTeams[]
 *
 * 与 Reports 页保持一致的容错策略：缺失字段显示占位，不臆造数值。
 *
 * 口径约定（审计 P2-20）：报表接口按 from/to 时间窗聚合（callTime 优先、缺失回退 uploadTime），
 * 因此「违规率」的分子（窗口内 VIOLATION_CONFIRMED）与分母（report.total）必须同窗口；
 * 不能用 overview.totalUploads（全量历史）做分母，否则长历史数据会把近期违规率系统性稀释。
 */

import { t } from '../i18n/index.js'
import { asObject, pick, toNumber, toText } from './shape.js'

function nonNegative(value) {
  const num = toNumber(value)
  return num !== null && num > 0 ? num : 0
}

/* ---------- KPI ---------- */

/**
 * 大屏 KPI。
 *
 * @param {object} overviewRaw GET /api/stats/overview 响应（全量口径：总量 / 今日 / 当前状态）
 * @param {object} reportRaw   GET /api/stats/report 响应（时间窗口口径：total / byStatus）
 * @returns {{total:number,today:number,needsReview:number,confirmed:number,windowTotal:number,violationRate:number|null}}
 *          total 为全量录音总数（KPI 展示），windowTotal 为报表窗口内录音数（违规率分母）
 */
export function normalizeScreenMetrics(overviewRaw, reportRaw) {
  const overview = asObject(overviewRaw)
  const report = asObject(reportRaw)
  const byStatus = asObject(report.byStatus)

  const overviewTotal = toNumber(pick(overview, ['totalUploads', 'total']))
  const reportTotal = toNumber(pick(report, ['total']))
  // KPI「录音总量」沿用 overview 的全量口径；overview 缺失时按报表 total 回退
  const total = overviewTotal ?? reportTotal ?? 0
  const today = nonNegative(pick(overview, ['todayUploads']))
  const needsReviewSource = pick(overview, ['statusCounts'])
  const needsReviewCounts = asObject(needsReviewSource)
  const needsReview = nonNegative(
    pick(needsReviewCounts, ['NEEDS_REVIEW']) ?? pick(byStatus, ['NEEDS_REVIEW'])
  )
  const confirmed = nonNegative(pick(byStatus, ['VIOLATION_CONFIRMED']))
  // 违规率分母：与 confirmed 同窗口的报表录音总数；窗口数据缺失时给 null（展示占位，不臆造 0%）
  const windowTotal = nonNegative(reportTotal)
  const violationRate = windowTotal > 0 ? confirmed / windowTotal : null

  return { total: nonNegative(total), today, needsReview, confirmed, windowTotal, violationRate }
}

/* ---------- 近 7 日趋势 ---------- */

export function normalizeScreenTrend(overviewRaw) {
  const overview = asObject(overviewRaw)
  const raw = pick(overview, ['last7Days', 'last7days', 'trend'])
  if (!Array.isArray(raw)) return []
  return raw
    .map((row) => {
      const data = asObject(row)
      return {
        date: toText(pick(data, ['date', 'day', 'label'])),
        count: nonNegative(pick(data, ['count', 'total', 'uploads']))
      }
    })
    .filter((row) => row.date)
}

/* ---------- 违规类型分布 ---------- */

export function normalizeViolationDistribution(reportRaw, limit = 8) {
  const report = asObject(reportRaw)
  const raw = pick(report, ['byViolationType', 'violationTypes'])
  let rows = []
  if (Array.isArray(raw)) {
    rows = raw.map((row) => {
      const data = asObject(row)
      return {
        key: toText(pick(data, ['type', 'key'])),
        label: toText(pick(data, ['label', 'name', 'type'])),
        count: nonNegative(pick(data, ['count', 'total']))
      }
    })
  } else if (raw && typeof raw === 'object') {
    rows = Object.entries(raw).map(([key, count]) => ({
      key,
      label: key,
      count: nonNegative(count)
    }))
  }
  rows = rows.filter((row) => row.count > 0)
  rows.sort((a, b) => b.count - a.count)
  const trimmed = Number.isFinite(Number(limit)) ? rows.slice(0, Math.max(1, Number(limit))) : rows
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  return trimmed.map((row) => ({
    ...row,
    label: row.label || row.key,
    percent: total > 0 ? row.count / total : 0
  }))
}

/* ---------- Top 坐席 / 团队 ---------- */

export function normalizeTopRows(raw, limit = 5) {
  if (!Array.isArray(raw)) return []
  const rows = raw
    .map((row) => {
      const data = asObject(row)
      return {
        name:
          toText(pick(data, ['name', 'label', 'agentName', 'teamName'])) || t('screen.notFilled'),
        count: nonNegative(pick(data, ['count', 'total']))
      }
    })
    .filter((row) => row.count > 0)
    .sort((a, b) => b.count - a.count)
  return Number.isFinite(Number(limit)) ? rows.slice(0, Math.max(1, Number(limit))) : rows
}

/* ---------- 格式化 ---------- */

/** 千分位整数 */
export function formatCount(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0'
  return String(Math.round(num)).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 0~1 小数 -> 百分比；null / 无效值 -> 占位（不显示误导性的 0%） */
export function formatRate(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!Number.isFinite(num) || num < 0) return '—'
  return `${Number((num * 100).toFixed(1))}%`
}
