/**
 * dashboard 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `dashboard.` 前缀。
 */
export default {
  zh: {
    // KPI
    'dashboard.kpi.total': '录音总数',
    'dashboard.kpi.totalSub': '今日新增 {count} 条',
    'dashboard.kpi.processing': '处理中',
    'dashboard.kpi.processingSub': '转写 / 初筛 / 复筛',
    'dashboard.kpi.needsReviewSub': '初筛与复筛均判违规',
    'dashboard.kpi.confirmedSub': '人工复检确认',
    'dashboard.kpi.compliantSub': '复筛误报放行 {count} 条',
    'dashboard.kpi.failedSub': '可重试',

    // 趋势与处置分布
    'dashboard.trend.title': '近 7 日上传趋势',
    'dashboard.trend.sub': '按上传日期统计',
    'dashboard.distribution.title': '处置分布',
    'dashboard.distribution.total': '共 {count} 条',
    'dashboard.distribution.processing': '处理中',

    // 最近录音
    'dashboard.recent.title': '最近录音',
    'dashboard.recent.viewAll': '查看全部',
    'dashboard.recent.file': '文件',
    'dashboard.recent.hits': '命中',
    'dashboard.recent.uploadTime': '上传时间',
    'dashboard.recent.emptyTitle': '暂无录音',
    'dashboard.recent.emptyHint': '上传录音后，最近处理的记录会显示在这里',

    // 知识库与闭环
    'dashboard.knowledge.title': '知识库与闭环',
    'dashboard.knowledge.dictionaryTotal': '敏感词总数',
    'dashboard.knowledge.dictionaryEnabled': '启用 {count}',
    'dashboard.knowledge.minedPending': 'AI 挖掘待审核',
    'dashboard.knowledge.minedSub': '来自确认违规录音',
    'dashboard.knowledge.violationCorpus': '违规语料',
    'dashboard.knowledge.compliantCorpus': '合规语料',
    'dashboard.knowledge.violationSub': 'few-shot 正例',
    'dashboard.knowledge.compliantSub': 'few-shot 反例',
    'dashboard.knowledge.loopTip':
      '人工复检结论自动写入语料库，作为 AI 复筛的 few-shot 参考；确认违规的录音会由 AI 提炼新敏感词入库（默认停用，审核后启用）。'
  },
  en: {
    // KPI
    'dashboard.kpi.total': 'Total recordings',
    'dashboard.kpi.totalSub': '{count} new today',
    'dashboard.kpi.processing': 'Processing',
    'dashboard.kpi.processingSub': 'Transcription, screening and re-screening',
    'dashboard.kpi.needsReviewSub': 'Flagged by both screening stages',
    'dashboard.kpi.confirmedSub': 'Confirmed by manual review',
    'dashboard.kpi.compliantSub': '{count} released as false positives',
    'dashboard.kpi.failedSub': 'Retry available',

    // Trend and disposition
    'dashboard.trend.title': 'Uploads in the last 7 days',
    'dashboard.trend.sub': 'Grouped by upload date',
    'dashboard.distribution.title': 'Disposition',
    'dashboard.distribution.total': '{count} total',
    'dashboard.distribution.processing': 'Processing',

    // Recent recordings
    'dashboard.recent.title': 'Recent Recordings',
    'dashboard.recent.viewAll': 'View all',
    'dashboard.recent.file': 'File',
    'dashboard.recent.hits': 'Hits',
    'dashboard.recent.uploadTime': 'Uploaded at',
    'dashboard.recent.emptyTitle': 'No recordings',
    'dashboard.recent.emptyHint': 'Processed recordings appear here after you upload them',

    // Knowledge base and feedback loop
    'dashboard.knowledge.title': 'Knowledge Base and Feedback Loop',
    'dashboard.knowledge.dictionaryTotal': 'Total sensitive words',
    'dashboard.knowledge.dictionaryEnabled': '{count} enabled',
    'dashboard.knowledge.minedPending': 'AI-mined pending review',
    'dashboard.knowledge.minedSub': 'From confirmed violations',
    'dashboard.knowledge.violationCorpus': 'Violation corpus',
    'dashboard.knowledge.compliantCorpus': 'Compliant corpus',
    'dashboard.knowledge.violationSub': 'few-shot positives',
    'dashboard.knowledge.compliantSub': 'few-shot negatives',
    'dashboard.knowledge.loopTip':
      'Manual review conclusions are written to the corpus automatically as few-shot references for AI screening; recordings confirmed as violations are mined for new sensitive words by AI (disabled by default and enabled after review).'
  }
}
