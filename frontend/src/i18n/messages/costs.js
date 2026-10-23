/**
 * costs 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `costs.` 前缀。
 */
export default {
  zh: {
    'costs.title': 'AI 用量与成本',
    'costs.subtitle': '基于 AI 调用留痕统计，成本为调用时单价的快照估算',
    'costs.rangeSeparator': '至',
    'costs.query': '查询',
    'costs.hint':
      '数据来自语义复筛与敏感词挖掘的真实调用（含失败重试）；单价在调用发生时快照，之后修改供应商价格不会回溯历史成本。清空日期范围表示全部时间。',

    'costs.kpiRequests': '请求次数',
    'costs.kpiInputTokens': '输入 Tokens',
    'costs.kpiOutputTokens': '输出 Tokens',
    'costs.kpiEstimatedCost': '预估成本',
    'costs.costSnapshotSub': '按调用时单价快照估算',

    'costs.statsLoadFailed': '统计加载失败',
    'costs.statsUnavailable': '统计暂不可用',
    'costs.retryHint': '请点击上方「重试」重新加载',

    'costs.byDay': '按日用量',
    'costs.byDayNote': '按调用日期汇总',
    'costs.colDate': '日期',
    'costs.colRequests': '请求',
    'costs.colInputTokens': '输入 Tokens',
    'costs.colOutputTokens': '输出 Tokens',
    'costs.colCost': '成本',
    'costs.noDailyData': '所选时间范围内暂无 AI 调用',
    'costs.noDailyHint': '录音进入 AI 语义复筛后，这里会按日汇总调用量与成本',

    'costs.byProvider': '按供应商',
    'costs.byModel': '按模型',
    'costs.costHighToLow': '成本从高到低',
    'costs.colProvider': '供应商',
    'costs.colModel': '模型',
    'costs.colTokensInOut': 'Tokens 入/出',
    'costs.noProviderData': '暂无供应商用量',
    'costs.noProviderHint': '启用并调用供应商后这里会按供应商汇总',
    'costs.noModelData': '暂无模型用量',
    'costs.noModelHint': '模型产生调用后这里会按模型汇总',

    'costs.last7Days': '最近 7 天',
    'costs.last30Days': '最近 30 天',
    'costs.last90Days': '最近 90 天',
    'costs.allTime': '全部时间',
    'costs.rangeEarliest': '最早',
    'costs.rangeUntilNow': '至今',

    'costs.errorForbidden': '当前账号无权限查看成本看板（需要 ADMIN）',
    'costs.errorEndpointMissing': '后端尚未提供 AI 用量接口（HTTP 404），请确认后端版本',
    'costs.errorFallback': '统计加载失败，请稍后重试'
  },
  en: {
    'costs.title': 'AI Usage & Cost',
    'costs.subtitle': 'Based on AI invocation logs; costs are estimated from unit prices snapshotted at call time',
    'costs.rangeSeparator': 'to',
    'costs.query': 'Search',
    'costs.hint':
      'Data comes from real semantic screening and keyword mining calls (including retries). Unit prices are snapshotted when each call happens, so later provider price changes do not reprice historical costs. Clearing the date range means all time.',

    'costs.kpiRequests': 'Requests',
    'costs.kpiInputTokens': 'Input tokens',
    'costs.kpiOutputTokens': 'Output tokens',
    'costs.kpiEstimatedCost': 'Estimated cost',
    'costs.costSnapshotSub': 'Estimated from unit prices snapshotted at call time',

    'costs.statsLoadFailed': 'Failed to load statistics',
    'costs.statsUnavailable': 'Statistics unavailable',
    'costs.retryHint': 'Click "Retry" above to reload',

    'costs.byDay': 'Daily usage',
    'costs.byDayNote': 'Aggregated by call date',
    'costs.colDate': 'Date',
    'costs.colRequests': 'Requests',
    'costs.colInputTokens': 'Input tokens',
    'costs.colOutputTokens': 'Output tokens',
    'costs.colCost': 'Cost',
    'costs.noDailyData': 'No AI calls in the selected range',
    'costs.noDailyHint':
      'Once recordings go through AI semantic screening, daily call volume and cost are summarized here',

    'costs.byProvider': 'By provider',
    'costs.byModel': 'By model',
    'costs.costHighToLow': 'Highest cost first',
    'costs.colProvider': 'Provider',
    'costs.colModel': 'Model',
    'costs.colTokensInOut': 'Tokens in/out',
    'costs.noProviderData': 'No provider usage',
    'costs.noProviderHint': 'Provider totals appear here once a provider is enabled and called',
    'costs.noModelData': 'No model usage',
    'costs.noModelHint': 'Model totals appear here once models are called',

    'costs.last7Days': 'Last 7 days',
    'costs.last30Days': 'Last 30 days',
    'costs.last90Days': 'Last 90 days',
    'costs.allTime': 'All time',
    'costs.rangeEarliest': 'earliest',
    'costs.rangeUntilNow': 'now',

    'costs.errorForbidden': 'You do not have permission to view the cost dashboard (ADMIN required)',
    'costs.errorEndpointMissing':
      'The backend does not provide the AI usage endpoint yet (HTTP 404). Please verify the backend version.',
    'costs.errorFallback': 'Failed to load statistics. Please try again later.'
  }
}
