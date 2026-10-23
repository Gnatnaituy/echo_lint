/**
 * 枚举 / 状态类标签（后端 code → 展示文案）。
 *
 * 这些 key 由 `src/constants.js`、`src/utils/*.js` 里的元数据对象以 getter 形式引用，
 * 读取 `.label` 时会按当前语言翻译，因此模板 / 计算属性可在语言切换后自动更新。
 *
 * 新增枚举时：在 zh / en 同步补 key，并在对应元数据对象里用 `get label() { return t(key) }`。
 */
export default {
  zh: {
    // 录音状态
    'meta.status.PENDING': '待处理',
    'meta.status.TRANSCRIBING': '转写中',
    'meta.status.DFA_CHECKING': '初筛中',
    'meta.status.AI_CHECKING': '语义复筛中',
    'meta.status.NEEDS_REVIEW': '待人工复检',
    'meta.status.COMPLIANT': '自动通过',
    'meta.status.VIOLATION_CONFIRMED': '确认违规',
    'meta.status.FALSE_POSITIVE': '误判放行',
    'meta.status.FAILED': '处理失败',

    // 违规类型
    'meta.violation.INSULT': '辱骂',
    'meta.violation.DISCRIMINATION': '歧视',
    'meta.violation.THREAT': '威胁',
    'meta.violation.HARASSMENT': '骚扰',
    'meta.violation.SEXUAL': '色情骚扰',
    'meta.violation.FRAUD': '诈骗诱导',
    'meta.violation.PRIVACY': '隐私泄露',
    'meta.violation.OTHER': '其他',

    // 敏感词严重程度
    'meta.severity.HIGH': '高',
    'meta.severity.MEDIUM': '中',
    'meta.severity.LOW': '低',

    // 敏感词来源
    'meta.source.MANUAL': '人工录入',
    'meta.source.MINED': 'AI 挖掘',

    // 通话方向
    'meta.direction.INBOUND': '呼入',
    'meta.direction.OUTBOUND': '呼出',
    'meta.direction.UNKNOWN': '未知',

    // 角色
    'meta.role.ADMIN': '管理员',
    'meta.role.REVIEWER': '复核员',
    'meta.role.VIEWER': '只读',
    'meta.roleShort.ADMIN': '管理',
    'meta.roleShort.REVIEWER': '复核',
    'meta.roleShort.VIEWER': '只读',
    'meta.roleDescription.ADMIN': '用户管理、操作审计、配置与复核',
    'meta.roleDescription.REVIEWER': '上传录音、认领与提交复核结论',
    'meta.roleDescription.VIEWER': '只读访问，不执行写操作',

    // 复核优先级
    'meta.priority.HIGH': '高',
    'meta.priority.NORMAL': '普通',
    'meta.priority.LOW': '低',

    // 异步任务状态
    'meta.taskStatus.PENDING': '排队中',
    'meta.taskStatus.RUNNING': '执行中',
    'meta.taskStatus.SUCCEEDED': '已成功',
    'meta.taskStatus.FAILED': '等待重试',
    'meta.taskStatus.DEAD': '最终失败',

    // 操作审计动作
    'meta.auditAction.LOGIN': '登录',
    'meta.auditAction.LOGOUT': '退出登录',
    'meta.auditAction.USER_CREATE': '创建用户',
    'meta.auditAction.USER_UPDATE': '更新用户',
    'meta.auditAction.USER_DISABLE': '禁用用户',
    'meta.auditAction.RECORDING_UPLOAD': '上传录音',
    'meta.auditAction.RECORDING_DELETE': '删除录音',
    'meta.auditAction.RECORDING_RETRY': '重试处理',
    'meta.auditAction.TASK_RETRY': '重试任务',
    'meta.auditAction.RECORDING_CLAIM': '认领录音',
    'meta.auditAction.RECORDING_ASSIGN': '指派录音',
    'meta.auditAction.REVIEW_SUBMIT': '提交复核',
    'meta.auditAction.REVIEW_REOPEN': '重开复核',
    'meta.auditAction.DICTIONARY_CREATE': '新增敏感词',
    'meta.auditAction.DICTIONARY_UPDATE': '更新敏感词',
    'meta.auditAction.DICTIONARY_DELETE': '删除敏感词',
    'meta.auditAction.DICTIONARY_IMPORT': '导入敏感词',
    'meta.auditAction.DICTIONARY_BULK_UPDATE': '批量启停敏感词',
    'meta.auditAction.CORPUS_CREATE': '新增语料',
    'meta.auditAction.CORPUS_DELETE': '删除语料',
    'meta.auditAction.REPORT_EXPORT': '报表导出',
    'meta.auditAction.SETTINGS_UPDATE': '修改设置',
    'meta.auditAction.NOTIFY_TEST': '测试通知',
    'meta.auditAction.PROVIDER_CREATE': '新增 AI 供应商',
    'meta.auditAction.PROVIDER_UPDATE': '更新 AI 供应商',
    'meta.auditAction.PROVIDER_DELETE': '删除 AI 供应商',
    'meta.auditAction.PROVIDER_TEST': '测试 AI 供应商',
    'meta.auditAction.MODEL_EVALUATE': '模型评测',
    'meta.auditAction.API_KEY_CREATE': '创建 API 密钥',
    'meta.auditAction.API_KEY_REVOKE': '停用 API 密钥',
    'meta.auditAction.OPEN_API_UPLOAD': '开放 API 上传录音',
    'meta.auditAction.KNOWLEDGE_CREATE': '新增知识库条目',
    'meta.auditAction.KNOWLEDGE_UPDATE': '更新知识库条目',
    'meta.auditAction.KNOWLEDGE_DELETE': '删除知识库条目',
    'meta.auditAction.RAG_SETTINGS_UPDATE': '修改 RAG 设置',
    'meta.auditAction.RAG_REINDEX': '重建 RAG 索引',
    'meta.auditAction.RAG_EVALUATE': 'RAG 对比评测',
    'meta.auditAction.RAG_DOCUMENT_IMPORT': '导入知识库文档',
    'meta.auditAction.PASSWORD_CHANGE': '修改密码',

    // 运营通知事件
    'meta.notifyEvent.REVIEW_CONFIRMED_VIOLATION': '复核确认违规',
    'meta.notifyEvent.RECORDING_FAILED': '录音处理失败',
    'meta.notifyEvent.REVIEW_BACKLOG': '待复核积压',
    'meta.notifyEvent.NOTIFY_TEST': '测试事件',

    // 知识库索引状态
    'meta.indexStatus.PENDING': '待处理',
    'meta.indexStatus.PARSING': '解析中',
    'meta.indexStatus.INDEXING': '索引中',
    'meta.indexStatus.READY': '已就绪',
    'meta.indexStatus.FAILED': '失败',

    // AI 调用阶段
    'meta.aiStage.SCREEN': '语义复筛',
    'meta.aiStage.MINING': '敏感词挖掘',
    'meta.aiStage.EMBED_QUERY': '检索向量化',
    'meta.aiStage.EMBED_INDEX': '索引向量化',

    // AI 调用状态
    'meta.aiStatus.SUCCESS': '成功',
    'meta.aiStatus.DEGRADED': '降级',
    'meta.aiStatus.FAILED': '失败',

    // AI 对话消息角色
    'meta.aiRole.SYSTEM': '系统提示词',
    'meta.aiRole.USER': '用户提示词',
    'meta.aiRole.ASSISTANT': '模型回复',
    'meta.aiRole.TOOL': '工具返回',
    'meta.aiRole.MESSAGE': '消息',

    // 语料标注 / 模型预测标签
    'meta.evaluationLabel.VIOLATION': '违规',
    'meta.evaluationLabel.COMPLIANT': '合规',

    // 批量上传状态
    'meta.uploadStatus.pending': '等待上传',
    'meta.uploadStatus.uploading': '上传中',
    'meta.uploadStatus.success': '上传成功',
    'meta.uploadStatus.error': '上传失败',
    'meta.uploadStatus.canceled': '已取消',

    // 录音手动推进 / 重新处理
    'meta.reprocess.retry': '重试',
    'meta.reprocess.advance': '推进处理',
    'meta.reprocess.processNow': '立即处理',
    'meta.reprocess.reprocess': '重新处理',
    'meta.reprocess.confirmRetry': '重新执行转写与稽核流程？（将覆盖失败记录）',
    'meta.reprocess.confirmAdvance':
      '该录音排队超过 2 分钟仍未开始，可能是上传后服务重启导致流水线丢失。立即处理？',
    'meta.reprocess.confirmProcessNow': '立即开始处理该录音？（正在排队时重复触发会被后端忽略）',
    'meta.reprocess.confirmReprocess': '该录音停留在「{status}」超过 2 分钟，疑似中断。重新处理？'
  },
  en: {
    // Recording status
    'meta.status.PENDING': 'Pending',
    'meta.status.TRANSCRIBING': 'Transcribing',
    'meta.status.DFA_CHECKING': 'Screening',
    'meta.status.AI_CHECKING': 'AI screening',
    'meta.status.NEEDS_REVIEW': 'Needs review',
    'meta.status.COMPLIANT': 'Auto-approved',
    'meta.status.VIOLATION_CONFIRMED': 'Violation confirmed',
    'meta.status.FALSE_POSITIVE': 'False positive',
    'meta.status.FAILED': 'Failed',

    // Violation types
    'meta.violation.INSULT': 'Insult',
    'meta.violation.DISCRIMINATION': 'Discrimination',
    'meta.violation.THREAT': 'Threat',
    'meta.violation.HARASSMENT': 'Harassment',
    'meta.violation.SEXUAL': 'Sexual harassment',
    'meta.violation.FRAUD': 'Fraud',
    'meta.violation.PRIVACY': 'Privacy leak',
    'meta.violation.OTHER': 'Other',

    // Dictionary severity
    'meta.severity.HIGH': 'High',
    'meta.severity.MEDIUM': 'Medium',
    'meta.severity.LOW': 'Low',

    // Dictionary source
    'meta.source.MANUAL': 'Manual entry',
    'meta.source.MINED': 'AI mined',

    // Call direction
    'meta.direction.INBOUND': 'Inbound',
    'meta.direction.OUTBOUND': 'Outbound',
    'meta.direction.UNKNOWN': 'Unknown',

    // Roles
    'meta.role.ADMIN': 'Administrator',
    'meta.role.REVIEWER': 'Reviewer',
    'meta.role.VIEWER': 'Read-only',
    'meta.roleShort.ADMIN': 'Admin',
    'meta.roleShort.REVIEWER': 'Review',
    'meta.roleShort.VIEWER': 'Read-only',
    'meta.roleDescription.ADMIN': 'User management, audit log, configuration and reviews',
    'meta.roleDescription.REVIEWER': 'Upload recordings, claim recordings and submit review results',
    'meta.roleDescription.VIEWER': 'Read-only access, no write operations',

    // Review priority
    'meta.priority.HIGH': 'High',
    'meta.priority.NORMAL': 'Normal',
    'meta.priority.LOW': 'Low',

    // Async task status
    'meta.taskStatus.PENDING': 'Queued',
    'meta.taskStatus.RUNNING': 'Running',
    'meta.taskStatus.SUCCEEDED': 'Succeeded',
    'meta.taskStatus.FAILED': 'Waiting to retry',
    'meta.taskStatus.DEAD': 'Dead (final failure)',

    // Audit actions
    'meta.auditAction.LOGIN': 'Sign in',
    'meta.auditAction.LOGOUT': 'Sign out',
    'meta.auditAction.USER_CREATE': 'Create user',
    'meta.auditAction.USER_UPDATE': 'Update user',
    'meta.auditAction.USER_DISABLE': 'Disable user',
    'meta.auditAction.RECORDING_UPLOAD': 'Upload recording',
    'meta.auditAction.RECORDING_DELETE': 'Delete recording',
    'meta.auditAction.RECORDING_RETRY': 'Retry processing',
    'meta.auditAction.TASK_RETRY': 'Retry task',
    'meta.auditAction.RECORDING_CLAIM': 'Claim recording',
    'meta.auditAction.RECORDING_ASSIGN': 'Assign recording',
    'meta.auditAction.REVIEW_SUBMIT': 'Submit review',
    'meta.auditAction.REVIEW_REOPEN': 'Reopen review',
    'meta.auditAction.DICTIONARY_CREATE': 'Create dictionary entry',
    'meta.auditAction.DICTIONARY_UPDATE': 'Update dictionary entry',
    'meta.auditAction.DICTIONARY_DELETE': 'Delete dictionary entry',
    'meta.auditAction.DICTIONARY_IMPORT': 'Import dictionary entries',
    'meta.auditAction.DICTIONARY_BULK_UPDATE': 'Bulk enable / disable dictionary entries',
    'meta.auditAction.CORPUS_CREATE': 'Create corpus sample',
    'meta.auditAction.CORPUS_DELETE': 'Delete corpus sample',
    'meta.auditAction.REPORT_EXPORT': 'Export report',
    'meta.auditAction.SETTINGS_UPDATE': 'Update settings',
    'meta.auditAction.NOTIFY_TEST': 'Test notification',
    'meta.auditAction.PROVIDER_CREATE': 'Create AI provider',
    'meta.auditAction.PROVIDER_UPDATE': 'Update AI provider',
    'meta.auditAction.PROVIDER_DELETE': 'Delete AI provider',
    'meta.auditAction.PROVIDER_TEST': 'Test AI provider',
    'meta.auditAction.MODEL_EVALUATE': 'Model evaluation',
    'meta.auditAction.API_KEY_CREATE': 'Create API key',
    'meta.auditAction.API_KEY_REVOKE': 'Revoke API key',
    'meta.auditAction.OPEN_API_UPLOAD': 'Upload recording via open API',
    'meta.auditAction.KNOWLEDGE_CREATE': 'Create knowledge entry',
    'meta.auditAction.KNOWLEDGE_UPDATE': 'Update knowledge entry',
    'meta.auditAction.KNOWLEDGE_DELETE': 'Delete knowledge entry',
    'meta.auditAction.RAG_SETTINGS_UPDATE': 'Update RAG settings',
    'meta.auditAction.RAG_REINDEX': 'Rebuild RAG index',
    'meta.auditAction.RAG_EVALUATE': 'RAG comparison evaluation',
    'meta.auditAction.RAG_DOCUMENT_IMPORT': 'Import knowledge document',
    'meta.auditAction.PASSWORD_CHANGE': 'Change password',

    // Notification events
    'meta.notifyEvent.REVIEW_CONFIRMED_VIOLATION': 'Violation confirmed by review',
    'meta.notifyEvent.RECORDING_FAILED': 'Recording processing failed',
    'meta.notifyEvent.REVIEW_BACKLOG': 'Review backlog',
    'meta.notifyEvent.NOTIFY_TEST': 'Test event',

    // Knowledge index status
    'meta.indexStatus.PENDING': 'Pending',
    'meta.indexStatus.PARSING': 'Parsing',
    'meta.indexStatus.INDEXING': 'Indexing',
    'meta.indexStatus.READY': 'Ready',
    'meta.indexStatus.FAILED': 'Failed',

    // AI invocation stages
    'meta.aiStage.SCREEN': 'Semantic screening',
    'meta.aiStage.MINING': 'Keyword mining',
    'meta.aiStage.EMBED_QUERY': 'Query embedding',
    'meta.aiStage.EMBED_INDEX': 'Index embedding',

    // AI invocation status
    'meta.aiStatus.SUCCESS': 'Success',
    'meta.aiStatus.DEGRADED': 'Degraded',
    'meta.aiStatus.FAILED': 'Failed',

    // AI message roles
    'meta.aiRole.SYSTEM': 'System prompt',
    'meta.aiRole.USER': 'User prompt',
    'meta.aiRole.ASSISTANT': 'Model reply',
    'meta.aiRole.TOOL': 'Tool response',
    'meta.aiRole.MESSAGE': 'Message',

    // Corpus / model prediction labels
    'meta.evaluationLabel.VIOLATION': 'Violation',
    'meta.evaluationLabel.COMPLIANT': 'Compliant',

    // Upload queue status
    'meta.uploadStatus.pending': 'Waiting',
    'meta.uploadStatus.uploading': 'Uploading',
    'meta.uploadStatus.success': 'Uploaded',
    'meta.uploadStatus.error': 'Upload failed',
    'meta.uploadStatus.canceled': 'Canceled',

    // Manual advance / reprocess actions
    'meta.reprocess.retry': 'Retry',
    'meta.reprocess.advance': 'Advance processing',
    'meta.reprocess.processNow': 'Process now',
    'meta.reprocess.reprocess': 'Reprocess',
    'meta.reprocess.confirmRetry':
      'Run transcription and audit again? This will overwrite the failed record.',
    'meta.reprocess.confirmAdvance':
      'This recording has been queued for over 2 minutes and its pipeline may have been lost after a service restart. Process it now?',
    'meta.reprocess.confirmProcessNow':
      'Start processing this recording now? Duplicate triggers are ignored by the backend while it is queued.',
    'meta.reprocess.confirmReprocess':
      'This recording has been stuck in "{status}" for over 2 minutes and may have been interrupted. Reprocess it?'
  }
}
