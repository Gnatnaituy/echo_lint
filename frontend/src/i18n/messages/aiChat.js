/**
 * aiChat 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `aiChat.` 前缀。
 */
export default {
  zh: {
    'aiChat.title': 'AI 对话记录',
    'aiChat.subtitle': '默认展示语义复筛对话；可切换阶段查看词挖掘 / embedding 调用',
    'aiChat.search': '查询',
    'aiChat.allStages': '全部阶段',
    'aiChat.allProviders': '全部供应商',
    'aiChat.allModels': '全部模型',
    'aiChat.allStatuses': '全部状态',
    'aiChat.recordingIdPlaceholder': '录音 ID',
    'aiChat.filterSuccess': '成功',
    'aiChat.filterFailed': '失败',
    'aiChat.listHint': '按时间倒序分页；点击任意行查看完整对话与原始 JSON。',

    // 列表列
    'aiChat.time': '时间',
    'aiChat.stage': '阶段',
    'aiChat.recording': '录音',
    'aiChat.providerModel': '供应商 / 模型',
    'aiChat.latency': '耗时',
    'aiChat.tokens': 'Tokens 入/出',
    'aiChat.cost': '成本',
    'aiChat.requestResponse': '请求/回复',
    'aiChat.emptyTitle': '暂无 AI 调用记录',
    'aiChat.emptyHint': '语义复筛、敏感词挖掘、embedding 调用都会自动留痕',

    // 详情
    'aiChat.detailTitle': 'AI 对话详情',
    'aiChat.detailSubtitle': '完整提示词、模型原始回复与调用用量',
    'aiChat.recordingLinked': '关联录音',
    'aiChat.provider': '供应商',
    'aiChat.model': '模型',
    'aiChat.requestResponseDetail': '请求 / 回复',
    'aiChat.charsCount.one': '{count} 字符',
    'aiChat.charsCount.other': '{count} 字符',
    'aiChat.endpoint': '端点',
    'aiChat.messagesTitle': '对话消息',
    'aiChat.messagesCount.one': '共 {count} 条，按请求顺序',
    'aiChat.messagesCount.other': '共 {count} 条，按请求顺序',
    'aiChat.requestContent': '请求内容',
    'aiChat.requestNotRecorded': '（请求体未留痕）',
    'aiChat.rawResponse': '模型原始回复',
    'aiChat.noResponse': '（无回复）',
    'aiChat.viewRawRequest': '查看原始请求 JSON',

    // 错误 / 校验
    'aiChat.recordingIdNumeric': '录音 ID 需为数字',
    'aiChat.loadFailed': 'AI 对话记录加载失败',
    'aiChat.detailLoadFailed': 'AI 对话详情加载失败'
  },
  en: {
    'aiChat.title': 'AI Conversations',
    'aiChat.subtitle':
      'Shows semantic screening conversations by default; switch stages to view keyword mining / embedding calls',
    'aiChat.search': 'Search',
    'aiChat.allStages': 'All stages',
    'aiChat.allProviders': 'All providers',
    'aiChat.allModels': 'All models',
    'aiChat.allStatuses': 'All statuses',
    'aiChat.recordingIdPlaceholder': 'Recording ID',
    'aiChat.filterSuccess': 'Success',
    'aiChat.filterFailed': 'Failed',
    'aiChat.listHint':
      'Sorted by time (newest first); click any row to view the full conversation and raw JSON.',

    // Table columns
    'aiChat.time': 'Time',
    'aiChat.stage': 'Stage',
    'aiChat.recording': 'Recording',
    'aiChat.providerModel': 'Provider / model',
    'aiChat.latency': 'Latency',
    'aiChat.tokens': 'Tokens in/out',
    'aiChat.cost': 'Cost',
    'aiChat.requestResponse': 'Request/response',
    'aiChat.emptyTitle': 'No AI invocations yet',
    'aiChat.emptyHint':
      'Semantic screening, keyword mining and embedding calls are all recorded automatically',

    // Details
    'aiChat.detailTitle': 'AI Conversation Details',
    'aiChat.detailSubtitle': 'Full prompt, raw model response and usage',
    'aiChat.recordingLinked': 'Linked recording',
    'aiChat.provider': 'Provider',
    'aiChat.model': 'Model',
    'aiChat.requestResponseDetail': 'Request / response',
    'aiChat.charsCount.one': '{count} character',
    'aiChat.charsCount.other': '{count} characters',
    'aiChat.endpoint': 'Endpoint',
    'aiChat.messagesTitle': 'Conversation messages',
    'aiChat.messagesCount.one': '{count} message, in request order',
    'aiChat.messagesCount.other': '{count} messages, in request order',
    'aiChat.requestContent': 'Request content',
    'aiChat.requestNotRecorded': '(request body not recorded)',
    'aiChat.rawResponse': 'Raw model response',
    'aiChat.noResponse': '(no response)',
    'aiChat.viewRawRequest': 'View raw request JSON',

    // Errors / validation
    'aiChat.recordingIdNumeric': 'Recording ID must be a number',
    'aiChat.loadFailed': 'Failed to load AI conversations',
    'aiChat.detailLoadFailed': 'Failed to load AI conversation details'
  }
}
