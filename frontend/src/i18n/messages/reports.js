/**
 * reports 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `reports.` 前缀。
 * 趋势图（TrendChart，工作台与数据大屏共用）的「峰值 / 近 7 日合计 / 单位」
 * 属于报表分析口径，也维护在本模块。
 */
export default {
  zh: {
    'reports.title': '报表与导出',
    'reports.rangeSeparator': '至',
    'reports.filterAgent': '坐席',
    'reports.filterTeam': '团队',
    'reports.allDirections': '全部方向',
    'reports.query': '查询',
    'reports.hintPrefix': '汇总卡与明细表按',
    'reports.hintDateRange': '日期范围',
    'reports.hintMiddle': '统计（GET /stats/report）；坐席 / 团队 / 方向筛选作用于上方 ',
    'reports.hintCsvExport': 'CSV 导出',
    'reports.hintSuffix': '（导出为 UTF-8 BOM，Excel 可直接打开中文）。清空日期范围表示全部时间。',

    'reports.kpiTotal': '录音总量',
    'reports.kpiConfirmed': '确认违规',
    'reports.violationRateSub': '违规率 {rate}%',
    'reports.kpiNeedsReview': '待人工复检',
    'reports.needsReviewSub': '初筛与复筛均判违规',
    'reports.kpiCompliant': '合规通过',
    'reports.falsePositiveSub': '误判放行 {count} 条',
    'reports.kpiSampled': '抽样数',
    'reports.sampledShareSub': '占录音总量 {percent}%',
    'reports.sampledFallbackSub': 'DFA 未命中的抽样抽检',

    'reports.statsLoadFailed': '统计加载失败',
    'reports.statsErrorFallback': '请求失败，请稍后重试',

    'reports.byAgent': '按坐席',
    'reports.byTeam': '按团队',
    'reports.sortNote': '按录音量排序，最多 10 条',
    'reports.colAgent': '坐席',
    'reports.colTeam': '团队',
    'reports.colRecordings': '录音数',
    'reports.colShare': '占比',
    'reports.statsUnavailable': '统计暂不可用',
    'reports.retryHint': '请点击上方「重试」重新加载',
    'reports.noAgentData': '暂无坐席数据',
    'reports.noAgentHint': '上传录音时填写「坐席」后即可按坐席统计',
    'reports.noTeamData': '暂无团队数据',
    'reports.noTeamHint': '上传录音时填写「团队」后即可按团队统计',

    'reports.byViolationType': '按违规类型',
    'reports.byViolationTypeNote': '仅统计人工确认违规（已复核）',
    'reports.colViolationType': '违规类型',
    'reports.colCount': '数量',
    'reports.colShareOfConfirmed': '占确认违规比例',
    'reports.noViolationData': '暂无违规类型数据',
    'reports.noViolationHint': '人工复检确认违规后，这里会按违规类型汇总',

    'reports.last7Days': '最近 7 天',
    'reports.last30Days': '最近 30 天',
    'reports.last90Days': '最近 90 天',
    'reports.allTime': '全部时间',
    'reports.rangeEarliest': '最早',
    'reports.rangeUntilNow': '至今',

    'reports.exportRecordings': '录音清单',
    'reports.exportViolations': '违规明细',
    'reports.exportReviews': '复核记录',
    'reports.exportStarted': '{label}已开始下载：{filename}',

    'reports.trendPeak': '峰值',
    'reports.trend7dTotal': '近 7 日合计',
    'reports.trendCountUnit': '条',
    'reports.trendTooltipUnit': '条录音'
  },
  en: {
    'reports.title': 'Reports & Export',
    'reports.rangeSeparator': 'to',
    'reports.filterAgent': 'Agent',
    'reports.filterTeam': 'Team',
    'reports.allDirections': 'All directions',
    'reports.query': 'Search',
    'reports.hintPrefix': 'Summary cards and detail tables are counted by ',
    'reports.hintDateRange': 'date range',
    'reports.hintMiddle': ' (GET /stats/report); agent / team / direction filters apply to the ',
    'reports.hintCsvExport': 'CSV export',
    'reports.hintSuffix':
      ' above (exported as UTF-8 with BOM so Excel opens Chinese correctly). Clearing the date range means all time.',

    'reports.kpiTotal': 'Total recordings',
    'reports.kpiConfirmed': 'Confirmed violations',
    'reports.violationRateSub': 'Violation rate {rate}%',
    'reports.kpiNeedsReview': 'Pending review',
    'reports.needsReviewSub': 'Flagged by both screening passes',
    'reports.kpiCompliant': 'Compliant',
    'reports.falsePositiveSub': 'False positives: {count}',
    'reports.kpiSampled': 'Sampled',
    'reports.sampledShareSub': '{percent}% of total recordings',
    'reports.sampledFallbackSub': 'Spot checks missed by DFA',

    'reports.statsLoadFailed': 'Failed to load statistics',
    'reports.statsErrorFallback': 'Request failed. Please try again later.',

    'reports.byAgent': 'By agent',
    'reports.byTeam': 'By team',
    'reports.sortNote': 'Sorted by recordings, top 10',
    'reports.colAgent': 'Agent',
    'reports.colTeam': 'Team',
    'reports.colRecordings': 'Recordings',
    'reports.colShare': 'Share',
    'reports.statsUnavailable': 'Statistics unavailable',
    'reports.retryHint': 'Click "Retry" above to reload',
    'reports.noAgentData': 'No agent data',
    'reports.noAgentHint': 'Agent statistics appear once recordings include an agent',
    'reports.noTeamData': 'No team data',
    'reports.noTeamHint': 'Team statistics appear once recordings include a team',

    'reports.byViolationType': 'By violation type',
    'reports.byViolationTypeNote': 'Confirmed violations only (reviewed)',
    'reports.colViolationType': 'Violation type',
    'reports.colCount': 'Count',
    'reports.colShareOfConfirmed': 'Share of confirmed violations',
    'reports.noViolationData': 'No violation type data',
    'reports.noViolationHint': 'Once manual review confirms violations, they are summarized here by type',

    'reports.last7Days': 'Last 7 days',
    'reports.last30Days': 'Last 30 days',
    'reports.last90Days': 'Last 90 days',
    'reports.allTime': 'All time',
    'reports.rangeEarliest': 'earliest',
    'reports.rangeUntilNow': 'now',

    'reports.exportRecordings': 'Recording list',
    'reports.exportViolations': 'Violation details',
    'reports.exportReviews': 'Review records',
    'reports.exportStarted': '{label} download started: {filename}',

    'reports.trendPeak': 'Peak',
    'reports.trend7dTotal': '7-day total',
    'reports.trendCountUnit': 'records',
    'reports.trendTooltipUnit': 'recordings'
  }
}
