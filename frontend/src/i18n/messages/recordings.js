/**
 * recordings 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `recordings.` 前缀。
 * 带数量的文案英文区分单复数时，成对提供 `.one` / `.other` 两条 key。
 */
export default {
  zh: {
    // 工具栏 / 筛选
    'recordings.filters.group': '状态筛选',
    'recordings.filters.processing': '处理中',
    'recordings.filters.needsReview': '待复检',
    'recordings.readOnly': '只读模式',
    'recordings.assigned.placeholder': '全部指派',
    'recordings.assigned.mine': '只看我的',
    'recordings.priority.placeholder': '全部优先级',
    'recordings.priority.option': '{label}优先级',
    'recordings.search.placeholder': '搜索文件名 / 转写内容',

    // 字段名（筛选项 / 上传表单共用）
    'recordings.field.agent': '坐席',
    'recordings.field.team': '团队',
    'recordings.field.callTime': '通话时间',
    'recordings.field.direction': '方向',
    'recordings.field.businessLine': '业务线',
    'recordings.field.callStartDate': '通话开始日期',
    'recordings.field.callEndDate': '通话结束日期',

    // 元数据筛选
    'recordings.meta.allDirections': '全部方向',
    'recordings.meta.clear': '清除条件',
    'recordings.meta.rangeSeparator': '至',
    'recordings.meta.last7Days': '最近 7 天',
    'recordings.meta.last30Days': '最近 30 天',
    'recordings.meta.last90Days': '最近 90 天',

    // 页面说明
    'recordings.hint.pipelinePrefix':
      '上传时可填写坐席、团队、通话时间、方向与业务线，列表与报表可按这些维度筛选；上传后自动执行 Whisper 转写 → DFA 初筛 → AI 语义复筛，初筛与复筛',
    'recordings.hint.pipelineAccent': '均判定违规',
    'recordings.hint.pipelineSuffix': '的录音进入人工复检。单文件 ≤ 25MB。',
    'recordings.hint.stuckPrefix':
      '若录音卡在「待处理 / 处理中」超过 2 分钟（例如上传后服务重启导致流水线丢失），可用行内',
    'recordings.hint.stuckAccent': '推进处理 / 重新处理',
    'recordings.hint.stuckSuffix': '手动触发。',
    'recordings.hint.readOnly':
      '只读账号可查看录音、转写与 AI 判定详情；上传、删除、认领与指派等写操作不可用。',

    // 列表
    'recordings.table.recording': '录音',
    'recordings.table.agentTeam': '坐席 / 团队',
    'recordings.table.callTime': '通话时间',
    'recordings.table.dfaHits': 'DFA 命中',
    'recordings.table.hitCount.one': '{count} 处',
    'recordings.table.hitCount.other': '{count} 处',
    'recordings.table.aiVerdict': 'AI 复筛结论',
    'recordings.table.priority': '优先级',
    'recordings.table.assignee': '指派',
    'recordings.table.me': '我',
    'recordings.table.unassigned': '未指派',
    'recordings.table.statusReviewer': '状态 / 复检人',
    'recordings.table.staleHint': '停留超过 2 分钟未变更，疑似中断；可用「重新处理」手动推进',
    'recordings.table.reviewedBy': '复检 · {name}',
    'recordings.aiVerdict.violation': '违规',
    'recordings.aiVerdict.compliant': '判定正常',
    'recordings.aiVerdict.screening': '复筛中',

    // 抽样录音
    'recordings.sampled.label': '抽样录音',
    'recordings.sampled.tooltip': 'DFA 未命中，按抽样比例进入 AI 语义复筛 / 人工复检',

    // 行内操作
    'recordings.actions.more': '更多',
    'recordings.actions.claim': '认领',
    'recordings.actions.assign': '指派复核人',
    'recordings.actions.goReview': '去复检',
    'recordings.actions.delete': '删除录音',
    'recordings.actions.reopen': '重开复检',
    'recordings.actions.goManualReview': '去人工复检',
    'recordings.actions.assignShort': '指派',
    'recordings.actions.reopenShort': '重开',
    'recordings.reopen.confirm': '重开为待人工复检？将清空最终结论并保留复核历史。',
    'recordings.delete.confirm': '确认删除该录音（含文件与日志）？',

    // 空态
    'recordings.empty.title': '还没有录音',
    'recordings.empty.hint': '上传客服通话录音，系统会自动完成转写、初筛与语义复筛',

    // 详情抽屉
    'recordings.detail.stereo': '双声道双轨',
    'recordings.detail.assignedTo': '指派：{name}',
    'recordings.detail.agent': '坐席：{name}',
    'recordings.detail.team': '团队：{name}',
    'recordings.detail.businessLine': '业务线：{name}',
    'recordings.detail.callTime': '通话：{time}',
    'recordings.detail.reviewProgress': '复核 {progress}',
    'recordings.detail.noAudio': '该录音无可播放文件（演示数据不含音频）',
    'recordings.detail.aiScreening': 'AI 语义复筛',
    'recordings.detail.verdictViolation': '判定违规',
    'recordings.detail.verdictCompliant': '判定正常',
    'recordings.detail.confidence': '置信度',
    'recordings.detail.model': '判定模型',
    'recordings.detail.degraded': '降级转人工',
    'recordings.detail.manualReview': '人工复检',
    'recordings.detail.timeline': '处理轨迹',
    'recordings.detail.noLogs': '暂无处理日志',
    'recordings.detail.aiInvocations': 'AI 调用详情',
    'recordings.detail.aiInvocationsHint.one': '完整提示词与原始回复，共 {count} 次',
    'recordings.detail.aiInvocationsHint.other': '完整提示词与原始回复，共 {count} 次',
    'recordings.detail.endpoint': '端点：',
    'recordings.detail.requestChars': '请求 {count} 字符',
    'recordings.detail.responseChars': '回复 {count} 字符',
    'recordings.detail.charCount': '{count} 字符',
    'recordings.detail.noRequestBody': '（请求体未留痕）',
    'recordings.detail.noReply': '（无回复）',

    // 转写视图
    'recordings.transcript.dialogue': '双栏对话',
    'recordings.transcript.sentence': '逐句对照',
    'recordings.transcript.full': '全文',
    'recordings.transcript.follow': '跟随播放',
    'recordings.transcript.empty': '（暂无转写文本）',

    // 人工复检
    'recordings.review.result': '结论',
    'recordings.review.reviewer': '复检人',
    'recordings.review.time': '时间',

    // 分声道试听
    'recordings.channel.mix': '混合',
    'recordings.channel.left': '左声道',
    'recordings.channel.right': '右声道',

    // 处理轨迹阶段
    'recordings.stage.pipeline': '流水线',
    'recordings.stage.transcribe': 'Whisper 转写',
    'recordings.stage.dfa': 'DFA 初筛',
    'recordings.stage.ai': 'AI 复筛',
    'recordings.stage.review': '人工复检',
    'recordings.stage.mining': '词典挖掘',
    'recordings.stage.rag': '知识库检索',

    // 上传对话框
    'recordings.upload.title': '上传录音',
    'recordings.upload.action': '上传录音',
    'recordings.upload.audioFiles': '音频文件',
    'recordings.upload.dropPrefix': '拖拽音频到此处，或 ',
    'recordings.upload.dropAccent': '点击选择',
    'recordings.upload.tip':
      '支持多选（最多 20 个），单文件 ≤ 25MB；按选择顺序逐个上传，元数据应用到本批全部文件',
    'recordings.upload.agentPlaceholder': '如 张三 / 工号 A1024',
    'recordings.upload.teamPlaceholder': '如 客服一组',
    'recordings.upload.callTimePlaceholder': '选择通话时间',
    'recordings.upload.directionPlaceholder': '未选择按未知处理',
    'recordings.upload.businessLinePlaceholder': '如 电商 / 金融分期',
    'recordings.upload.queueSummary.one': '共 {count} 个文件 · 已完成 {done} / {count}',
    'recordings.upload.queueSummary.other': '共 {count} 个文件 · 已完成 {done} / {count}',
    'recordings.upload.overallProgress': '总进度 {percent}%',
    'recordings.upload.summary': '本批完成：成功 {success} · 失败 {error} · 取消 {canceled}',
    'recordings.upload.metadataTip':
      '元数据均为选填，后续可在列表与报表中按坐席 / 团队 / 通话时间 / 方向筛选；上传成功后表单自动清空。',
    'recordings.upload.cancelRemaining': '取消剩余',
    'recordings.upload.start': '开始上传',
    'recordings.upload.uploadingProgress': '上传中 {done} / {count}',
    'recordings.upload.successMessage.one': '本批成功上传 {count} 个文件，已进入稽核流水线',
    'recordings.upload.successMessage.other': '本批成功上传 {count} 个文件，已进入稽核流水线',
    'recordings.upload.failureMessage.one': '{count} 个文件上传失败，可在列表中重试或重新选择',
    'recordings.upload.failureMessage.other': '{count} 个文件上传失败，可在列表中重试或重新选择',
    'recordings.upload.tooMany': '一次最多上传 20 个文件，请分批上传',
    'recordings.upload.canceledRemaining': '已取消剩余文件（当前文件仍会上传完成）',
    'recordings.upload.noFilesSelected': '请选择要上传的音频文件',
    'recordings.upload.noUploadable': '没有可上传的文件：请移除超过 25MB 的文件后重试',
    'recordings.upload.batchDone': '本批已全部处理完成，可关闭窗口或重新选择文件',
    'recordings.upload.failed': '上传失败',
    'recordings.upload.tooLarge': '超过 25MB（Whisper 单文件上限）',
    'recordings.upload.unknownFile': '未知文件',

    // 操作反馈
    'recordings.messages.deleted': '已删除',
    'recordings.messages.claimed': '已认领，可在人工复检页处理',
    'recordings.messages.reopened': '已重开为待人工复检',
    'recordings.messages.noAudioSeek': '该录音无音频文件，句子起始时间 {time}',
    'recordings.reprocess.triggered': '{action}已触发，正在重新执行流水线',
    'recordings.reprocess.fallbackAction': '处理'
  },
  en: {
    // Toolbar / filters
    'recordings.filters.group': 'Status filters',
    'recordings.filters.processing': 'Processing',
    'recordings.filters.needsReview': 'Pending review',
    'recordings.readOnly': 'Read-only',
    'recordings.assigned.placeholder': 'All assignees',
    'recordings.assigned.mine': 'Only mine',
    'recordings.priority.placeholder': 'All priorities',
    'recordings.priority.option': '{label} priority',
    'recordings.search.placeholder': 'Search file name / transcript',

    // Field names (filters / upload form)
    'recordings.field.agent': 'Agent',
    'recordings.field.team': 'Team',
    'recordings.field.callTime': 'Call time',
    'recordings.field.direction': 'Direction',
    'recordings.field.businessLine': 'Business line',
    'recordings.field.callStartDate': 'Call start date',
    'recordings.field.callEndDate': 'Call end date',

    // Metadata filters
    'recordings.meta.allDirections': 'All directions',
    'recordings.meta.clear': 'Clear filters',
    'recordings.meta.rangeSeparator': 'to',
    'recordings.meta.last7Days': 'Last 7 days',
    'recordings.meta.last30Days': 'Last 30 days',
    'recordings.meta.last90Days': 'Last 90 days',

    // Page hints
    'recordings.hint.pipelinePrefix':
      'You can add agent, team, call time, direction and business line when uploading, and filter the list and reports by these dimensions. After upload the system runs Whisper transcription → DFA screening → AI semantic screening automatically. Recordings flagged by ',
    'recordings.hint.pipelineAccent': 'both screening stages',
    'recordings.hint.pipelineSuffix': ' are sent to manual review. Max 25 MB per file.',
    'recordings.hint.stuckPrefix':
      'If a recording stays in "Pending / Processing" for over 2 minutes (for example, its pipeline was lost after a service restart), use the inline ',
    'recordings.hint.stuckAccent': 'Advance processing / Reprocess',
    'recordings.hint.stuckSuffix': ' action to trigger it manually.',
    'recordings.hint.readOnly':
      'Read-only accounts can view recordings, transcripts and AI verdicts; write actions such as upload, delete, claim and assign are unavailable.',

    // Table
    'recordings.table.recording': 'Recording',
    'recordings.table.agentTeam': 'Agent / Team',
    'recordings.table.callTime': 'Call time',
    'recordings.table.dfaHits': 'DFA hits',
    'recordings.table.hitCount.one': '{count} hit',
    'recordings.table.hitCount.other': '{count} hits',
    'recordings.table.aiVerdict': 'AI verdict',
    'recordings.table.priority': 'Priority',
    'recordings.table.assignee': 'Assigned',
    'recordings.table.me': 'Me',
    'recordings.table.unassigned': 'Unassigned',
    'recordings.table.statusReviewer': 'Status / Reviewer',
    'recordings.table.staleHint':
      'Unchanged for over 2 minutes and possibly interrupted; use "Reprocess" to advance it manually.',
    'recordings.table.reviewedBy': 'Reviewed · {name}',
    'recordings.aiVerdict.violation': 'Violation',
    'recordings.aiVerdict.compliant': 'No violation',
    'recordings.aiVerdict.screening': 'Screening',

    // Sampled recordings
    'recordings.sampled.label': 'Sampled',
    'recordings.sampled.tooltip': 'No DFA hit; sampled for AI screening / manual review',

    // Row actions
    'recordings.actions.more': 'More',
    'recordings.actions.claim': 'Claim',
    'recordings.actions.assign': 'Assign reviewer',
    'recordings.actions.goReview': 'Review now',
    'recordings.actions.delete': 'Delete recording',
    'recordings.actions.reopen': 'Reopen review',
    'recordings.actions.goManualReview': 'Go to manual review',
    'recordings.actions.assignShort': 'Assign',
    'recordings.actions.reopenShort': 'Reopen',
    'recordings.reopen.confirm':
      'Reopen as pending manual review? The final verdict will be cleared and review history kept.',
    'recordings.delete.confirm': 'Delete this recording (including file and logs)?',

    // Empty state
    'recordings.empty.title': 'No recordings yet',
    'recordings.empty.hint':
      'Upload customer service call recordings and the system will handle transcription, screening and semantic review automatically.',

    // Detail drawer
    'recordings.detail.stereo': 'Stereo dual-track',
    'recordings.detail.assignedTo': 'Assigned: {name}',
    'recordings.detail.agent': 'Agent: {name}',
    'recordings.detail.team': 'Team: {name}',
    'recordings.detail.businessLine': 'Business line: {name}',
    'recordings.detail.callTime': 'Call: {time}',
    'recordings.detail.reviewProgress': 'Review {progress}',
    'recordings.detail.noAudio': 'No playable file for this recording (demo data has no audio)',
    'recordings.detail.aiScreening': 'AI screening',
    'recordings.detail.verdictViolation': 'Violation',
    'recordings.detail.verdictCompliant': 'No violation',
    'recordings.detail.confidence': 'Confidence',
    'recordings.detail.model': 'Model',
    'recordings.detail.degraded': 'Degraded to manual review',
    'recordings.detail.manualReview': 'Manual review',
    'recordings.detail.timeline': 'Processing timeline',
    'recordings.detail.noLogs': 'No processing logs yet',
    'recordings.detail.aiInvocations': 'AI invocation details',
    'recordings.detail.aiInvocationsHint.one': 'Full prompts and raw replies, {count} invocation',
    'recordings.detail.aiInvocationsHint.other': 'Full prompts and raw replies, {count} invocations',
    'recordings.detail.endpoint': 'Endpoint: ',
    'recordings.detail.requestChars': 'Request {count} chars',
    'recordings.detail.responseChars': 'Reply {count} chars',
    'recordings.detail.charCount': '{count} chars',
    'recordings.detail.noRequestBody': '(Request body not recorded)',
    'recordings.detail.noReply': '(No reply)',

    // Transcript views
    'recordings.transcript.dialogue': 'Dialogue',
    'recordings.transcript.sentence': 'Sentence view',
    'recordings.transcript.full': 'Full text',
    'recordings.transcript.follow': 'Follow playback',
    'recordings.transcript.empty': '(No transcript yet)',

    // Manual review
    'recordings.review.result': 'Verdict',
    'recordings.review.reviewer': 'Reviewer',
    'recordings.review.time': 'Time',

    // Channel playback
    'recordings.channel.mix': 'Mixed',
    'recordings.channel.left': 'Left channel',
    'recordings.channel.right': 'Right channel',

    // Timeline stages
    'recordings.stage.pipeline': 'Pipeline',
    'recordings.stage.transcribe': 'Whisper transcription',
    'recordings.stage.dfa': 'DFA screening',
    'recordings.stage.ai': 'AI screening',
    'recordings.stage.review': 'Manual review',
    'recordings.stage.mining': 'Dictionary mining',
    'recordings.stage.rag': 'Knowledge retrieval',

    // Upload dialog
    'recordings.upload.title': 'Upload Recordings',
    'recordings.upload.action': 'Upload recordings',
    'recordings.upload.audioFiles': 'Audio files',
    'recordings.upload.dropPrefix': 'Drag audio files here, or ',
    'recordings.upload.dropAccent': 'click to select',
    'recordings.upload.tip':
      'Multiple files supported (up to 20), max 25 MB each. Files upload one by one in selection order; metadata applies to the whole batch.',
    'recordings.upload.agentPlaceholder': 'e.g. Alex Chen / ID A1024',
    'recordings.upload.teamPlaceholder': 'e.g. Support Team 1',
    'recordings.upload.callTimePlaceholder': 'Select call time',
    'recordings.upload.directionPlaceholder': 'Unknown if left empty',
    'recordings.upload.businessLinePlaceholder': 'e.g. E-commerce / Consumer finance',
    'recordings.upload.queueSummary.one': '{count} file · {done} / {count} done',
    'recordings.upload.queueSummary.other': '{count} files · {done} / {count} done',
    'recordings.upload.overallProgress': 'Overall {percent}%',
    'recordings.upload.summary':
      'Batch finished: {success} succeeded · {error} failed · {canceled} canceled',
    'recordings.upload.metadataTip':
      'All metadata is optional. You can filter the list and reports by agent / team / call time / direction later; the form clears after a successful upload.',
    'recordings.upload.cancelRemaining': 'Cancel remaining',
    'recordings.upload.start': 'Start upload',
    'recordings.upload.uploadingProgress': 'Uploading {done} / {count}',
    'recordings.upload.successMessage.one': 'Uploaded {count} file to the audit pipeline',
    'recordings.upload.successMessage.other': 'Uploaded {count} files to the audit pipeline',
    'recordings.upload.failureMessage.one':
      '{count} file failed to upload. Retry from the list or select files again.',
    'recordings.upload.failureMessage.other':
      '{count} files failed to upload. Retry from the list or select files again.',
    'recordings.upload.tooMany': 'Up to 20 files per upload. Please upload in batches.',
    'recordings.upload.canceledRemaining':
      'Remaining files canceled (the current file will finish uploading)',
    'recordings.upload.noFilesSelected': 'Select audio files to upload',
    'recordings.upload.noUploadable': 'No uploadable files. Remove files over 25 MB and try again.',
    'recordings.upload.batchDone':
      'This batch is finished. Close the dialog or select new files.',
    'recordings.upload.failed': 'Upload failed',
    'recordings.upload.tooLarge': 'Exceeds 25 MB (Whisper per-file limit)',
    'recordings.upload.unknownFile': 'Unknown file',

    // Action feedback
    'recordings.messages.deleted': 'Deleted',
    'recordings.messages.claimed': 'Claimed. You can process it on the Manual Review page.',
    'recordings.messages.reopened': 'Reopened as pending manual review',
    'recordings.messages.noAudioSeek': 'This recording has no audio file; the sentence starts at {time}',
    'recordings.reprocess.triggered': '{action} triggered. Running the pipeline again.',
    'recordings.reprocess.fallbackAction': 'Processing'
  }
}
