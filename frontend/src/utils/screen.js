/**
 * 数据大屏（/screen）数据归一化与格式化。
 *
 * 数据来源：
 * - GET /api/stats/overview -> totalUploads / todayUploads / statusCounts / last7Days[{label,count}]
 * - GET /api/stats/report   -> total / byStatus / byViolationType[] / topAgents[] / topTeams[]
 *
 * 与 Reports 页保持一致的容错策略：缺失字段显示占位，不臆造数值。
 */

import { asObject, pick, toNumber, toText } from './shape.js'

function nonNegative(value) {
  const num = toNumber(value)
  return num !== null && num > 0 ? num : 0
}

/* ---------- KPI ---------- */

export function normalizeScreenMetrics(overviewRaw, reportRaw) {
  const overview = asObject(overviewRaw)
  const report = asObject(reportRaw)
  const byStatus = asObject(report.byStatus)

  const total = toNumber(pick(overview, ['totalUploads', 'total']))
    ?? toNumber(pick(report, ['total']))
    ?? 0
  const today = nonNegative(pick(overview, ['todayUploads']))
  const needsReviewSource = pick(overview, ['statusCounts'])
  const needsReviewCounts = asObject(needsReviewSource)
  const needsReview = nonNegative(
    pick(needsReviewCounts, ['NEEDS_REVIEW']) ?? pick(byStatus, ['NEEDS_REVIEW'])
  )
  const confirmed = nonNegative(
    pick(byStatus, ['VIOLATION_CONFIRMED'])
  )
  const violationRate = total > 0 ? confirmed / total : null

  return { total: nonNegative(total), today, needsReview, confirmed, violationRate }
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
        label: toText(pick(data, ['label', 'date', 'day'])),
        count: nonNegative(pick(data, ['count', 'total', 'uploads']))
      }
    })
    .filter((row) => row.label)
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
        name: toText(pick(data, ['name', 'label', 'agentName', 'teamName'])) || '未填写',
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
