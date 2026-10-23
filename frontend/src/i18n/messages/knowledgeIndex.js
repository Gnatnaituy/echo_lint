/**
 * 知识库页「索引明细」Tab 文案（zh / en 必须一一对应）。
 *
 * key 统一使用 `knowledgeIndex.` 前缀；索引状态 / 来源 / 分块数等枚举与字段标签
 * 复用 `messages/meta.js`、`messages/knowledge.js` 的既有 key，不重复定义。
 */
export default {
  zh: {
    // 页面 / 工具栏
    'knowledgeIndex.pageTitle': '索引明细',
    'knowledgeIndex.intro':
      '查看全部知识库文档的索引状态与分块明细；处理中的条目会自动刷新。',
    'knowledgeIndex.keywordPlaceholder': '标题 / 来源文件',
    'knowledgeIndex.allIndexStatuses': '全部索引状态',
    'knowledgeIndex.allSourceTypes': '全部来源',
    'knowledgeIndex.query': '查询',
    'knowledgeIndex.autoRefreshHint': '当前页存在处理中条目时，每 {seconds} 秒自动刷新',
    'knowledgeIndex.lastUpdated': '最近刷新 {time}',

    // 汇总卡
    'knowledgeIndex.kpi.total': '文档总数',
    'knowledgeIndex.kpi.totalSub': '知识库全部条目',
    'knowledgeIndex.kpi.ready': '就绪',
    'knowledgeIndex.kpi.readySub': '已生成向量分块',
    'knowledgeIndex.kpi.processing': '处理中',
    'knowledgeIndex.kpi.processingSub': '待处理 / 解析中 / 索引中',
    'knowledgeIndex.kpi.failed': '失败',
    'knowledgeIndex.kpi.failedSub': '需要重建索引',
    'knowledgeIndex.summary.embedding': '向量模型',
    'knowledgeIndex.summary.embeddingValue': '向量模型：{value}',
    'knowledgeIndex.summary.lastReindex': '最近重建：{time}',
    'knowledgeIndex.summary.vectorAvailable': '向量检索可用',
    'knowledgeIndex.summary.vectorUnavailable': '向量检索不可用',
    'knowledgeIndex.summary.unavailable': '汇总数据暂不可用',

    // 列表
    'knowledgeIndex.column.document': '文档',
    'knowledgeIndex.openChunks': '分块明细',
    'knowledgeIndex.emptyTitle': '暂无文档',
    'knowledgeIndex.emptyFilteredTitle': '没有匹配的文档',
    'knowledgeIndex.emptyFilteredHint': '试试清空关键字、索引状态或来源筛选',
    'knowledgeIndex.emptyHint': '知识库还没有文档，可先在「知识库」页导入或新建条目',
    'knowledgeIndex.reindexQueued': '已提交「{title}」重建索引，后台处理中',
    'knowledgeIndex.backgroundFailed': '自动刷新失败，稍后自动重试',

    // 错误兜底
    'knowledgeIndex.loadFailed': '索引明细加载失败，请稍后重试',
    'knowledgeIndex.forbidden': '当前账号无权限查看索引明细（需要管理员）',
    'knowledgeIndex.notAvailable': '后端尚未提供索引明细接口（HTTP 404），请确认后端版本',

    // 分块明细对话框
    'knowledgeIndex.chunks.title': '分块明细',
    'knowledgeIndex.chunks.fingerprint': '索引指纹',
    'knowledgeIndex.chunks.embedding': '向量模型',
    'knowledgeIndex.chunks.totalChunks': '分块数 {count}',
    'knowledgeIndex.chunks.column.content': '内容',
    'knowledgeIndex.chunks.column.contentHash': '内容哈希',
    'knowledgeIndex.chunks.column.embedding': '向量',
    'knowledgeIndex.chunks.emptyTitle': '暂无分块',
    'knowledgeIndex.chunks.emptyHint': '可能尚未索引或正文为空',
    'knowledgeIndex.chunks.emptyContent': '（无内容）',
    'knowledgeIndex.chunks.loadFailed': '分块明细加载失败，请稍后重试',
    'knowledgeIndex.chunks.forbidden': '当前账号无权限查看分块明细（需要管理员）',
    'knowledgeIndex.chunks.notAvailable': '后端尚未提供分块明细接口（HTTP 404），请确认后端版本'
  },
  en: {
    // Page / toolbar
    'knowledgeIndex.pageTitle': 'Index details',
    'knowledgeIndex.intro':
      'Review the index status and chunk details of every knowledge document; entries in progress refresh automatically.',
    'knowledgeIndex.keywordPlaceholder': 'Title / source file',
    'knowledgeIndex.allIndexStatuses': 'All index statuses',
    'knowledgeIndex.allSourceTypes': 'All sources',
    'knowledgeIndex.query': 'Search',
    'knowledgeIndex.autoRefreshHint':
      'Auto-refreshes every {seconds}s while the current page has entries in progress',
    'knowledgeIndex.lastUpdated': 'Updated {time}',

    // Summary cards
    'knowledgeIndex.kpi.total': 'Documents',
    'knowledgeIndex.kpi.totalSub': 'All knowledge entries',
    'knowledgeIndex.kpi.ready': 'Ready',
    'knowledgeIndex.kpi.readySub': 'Vector chunks built',
    'knowledgeIndex.kpi.processing': 'Processing',
    'knowledgeIndex.kpi.processingSub': 'Pending / parsing / indexing',
    'knowledgeIndex.kpi.failed': 'Failed',
    'knowledgeIndex.kpi.failedSub': 'Needs a rebuild',
    'knowledgeIndex.summary.embedding': 'Embedding model',
    'knowledgeIndex.summary.embeddingValue': 'Embedding model: {value}',
    'knowledgeIndex.summary.lastReindex': 'Last reindex: {time}',
    'knowledgeIndex.summary.vectorAvailable': 'Vector search available',
    'knowledgeIndex.summary.vectorUnavailable': 'Vector search unavailable',
    'knowledgeIndex.summary.unavailable': 'Summary data unavailable',

    // List
    'knowledgeIndex.column.document': 'Document',
    'knowledgeIndex.openChunks': 'Chunk details',
    'knowledgeIndex.emptyTitle': 'No documents yet',
    'knowledgeIndex.emptyFilteredTitle': 'No matching documents',
    'knowledgeIndex.emptyFilteredHint':
      'Try clearing the keyword, index status or source filters',
    'knowledgeIndex.emptyHint':
      'The knowledge base has no documents yet. Import or create entries on the Knowledge Base page first',
    'knowledgeIndex.reindexQueued': 'Rebuild requested for "{title}"; processing in the background',
    'knowledgeIndex.backgroundFailed': 'Auto-refresh failed; retrying automatically later',

    // Error fallbacks
    'knowledgeIndex.loadFailed': 'Failed to load index details. Please try again later',
    'knowledgeIndex.forbidden':
      'Your account does not have permission to view index details (administrator required)',
    'knowledgeIndex.notAvailable':
      'The backend does not provide the index details API yet (HTTP 404). Please check the backend version',

    // Chunk details dialog
    'knowledgeIndex.chunks.title': 'Chunk details',
    'knowledgeIndex.chunks.fingerprint': 'Index fingerprint',
    'knowledgeIndex.chunks.embedding': 'Embedding model',
    'knowledgeIndex.chunks.totalChunks': '{count} chunks',
    'knowledgeIndex.chunks.column.content': 'Content',
    'knowledgeIndex.chunks.column.contentHash': 'Content hash',
    'knowledgeIndex.chunks.column.embedding': 'Embedding',
    'knowledgeIndex.chunks.emptyTitle': 'No chunks yet',
    'knowledgeIndex.chunks.emptyHint':
      'The entry may not be indexed yet or its content is empty',
    'knowledgeIndex.chunks.emptyContent': '(No content)',
    'knowledgeIndex.chunks.loadFailed': 'Failed to load chunk details. Please try again later',
    'knowledgeIndex.chunks.forbidden':
      'Your account does not have permission to view chunk details (administrator required)',
    'knowledgeIndex.chunks.notAvailable':
      'The backend does not provide the chunk details API yet (HTTP 404). Please check the backend version'
  }
}
