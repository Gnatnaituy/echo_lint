/**
 * corpus 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `corpus.` 前缀。
 */
export default {
  zh: {
    // 概览
    'corpus.kpi.total': '语料总数',
    'corpus.kpi.totalSub': '人工复检自动沉淀',
    'corpus.kpi.violation': '违规样本',
    'corpus.kpi.violationSub': 'few-shot 正例',
    'corpus.kpi.compliant': '合规样本',
    'corpus.kpi.compliantSub': 'few-shot 反例',

    // 闭环说明
    'corpus.loop.title': '人工复检 → 语料库 → AI 复筛 的自增强闭环',
    'corpus.loop.step1': '人工复检提交结论，自动写入语料库',
    'corpus.loop.step2': 'AI 复筛时取最新违规 / 合规样例作为 few-shot 提示',
    'corpus.loop.step3': '确认违规的录音另由 AI 挖掘新敏感词（停用待审核）',
    'corpus.exportJson': '导出 JSON',

    // 工具栏
    'corpus.searchPlaceholder': '搜索语料文本 / 理由',
    'corpus.create': '新增语料',

    // 语料表
    'corpus.table.label': '标注',
    'corpus.table.violationType': '违规类型',
    'corpus.table.transcript': '语料文本',
    'corpus.table.reason': '标注理由',
    'corpus.table.source': '来源',
    'corpus.confirmDelete': '确认删除该条语料？',
    'corpus.empty.title': '暂无语料',
    'corpus.empty.hint': '完成人工复检后，结论会自动沉淀到这里',

    // 语料来源
    'corpus.source.REVIEW': '人工复检',
    'corpus.source.SEED': '初始合成',
    'corpus.source.MANUAL': '手工录入',

    // 新增语料
    'corpus.dialog.createTitle': '新增语料',
    'corpus.form.transcriptPlaceholder': '粘贴一段客服录音转写文本',
    'corpus.form.reasonPlaceholder': '为什么这么判（可选，作为 AI 参考依据）',

    // 操作反馈
    'corpus.messages.transcriptRequired': '请输入语料文本',
    'corpus.messages.created': '已新增语料',
    'corpus.messages.deleted': '已删除',
    'corpus.messages.exportFailed': '导出失败'
  },
  en: {
    // Overview
    'corpus.kpi.total': 'Total samples',
    'corpus.kpi.totalSub': 'Auto-collected from manual reviews',
    'corpus.kpi.violation': 'Violation samples',
    'corpus.kpi.violationSub': 'few-shot positives',
    'corpus.kpi.compliant': 'Compliant samples',
    'corpus.kpi.compliantSub': 'few-shot negatives',

    // Feedback loop
    'corpus.loop.title': 'Manual review → Corpus → AI screening self-reinforcing loop',
    'corpus.loop.step1': 'Reviewer conclusions are written to the corpus automatically',
    'corpus.loop.step2': 'AI screening uses the latest violation / compliant samples as few-shot prompts',
    'corpus.loop.step3': 'Confirmed violations are mined for new sensitive words by AI (disabled pending review)',
    'corpus.exportJson': 'Export JSON',

    // Toolbar
    'corpus.searchPlaceholder': 'Search transcript or reason',
    'corpus.create': 'New sample',

    // Corpus table
    'corpus.table.label': 'Label',
    'corpus.table.violationType': 'Violation type',
    'corpus.table.transcript': 'Transcript',
    'corpus.table.reason': 'Review reason',
    'corpus.table.source': 'Source',
    'corpus.confirmDelete': 'Delete this corpus sample?',
    'corpus.empty.title': 'No corpus samples',
    'corpus.empty.hint': 'Conclusions from manual reviews are collected here automatically',

    // Corpus source
    'corpus.source.REVIEW': 'Manual review',
    'corpus.source.SEED': 'Synthetic seed',
    'corpus.source.MANUAL': 'Manual entry',

    // Create corpus sample
    'corpus.dialog.createTitle': 'New Corpus Sample',
    'corpus.form.transcriptPlaceholder': 'Paste a customer service call transcript',
    'corpus.form.reasonPlaceholder': 'Why was it judged this way? (optional, used as an AI reference)',

    // Feedback messages
    'corpus.messages.transcriptRequired': 'Please enter a transcript',
    'corpus.messages.created': 'Sample created',
    'corpus.messages.deleted': 'Deleted',
    'corpus.messages.exportFailed': 'Export failed'
  }
}
