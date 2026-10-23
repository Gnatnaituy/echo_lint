/**
 * review 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `review.` 前缀。
 */
export default {
  zh: {
    // 页签 / 队列筛选
    'review.tabs.pending': '待复检',
    'review.tabs.history': '已复检',
    'review.filters.allPriorities': '全部优先级',
    'review.filters.priorityOption': '{label}优先级',
    'review.filters.mineOnly': '只看我的',

    // 复检队列
    'review.queue.title': '复检队列',
    'review.queue.hint': '初筛 ∩ 复筛均判违规才进入复检队列',
    'review.queue.searchPlaceholder': '筛选文件/转写',
    'review.queue.searchTitle': '输入后按回车筛选（文件名或转写文本）',
    'review.queue.emptyFilteredTitle': '没有匹配的录音',
    'review.queue.emptyFilteredHint': '试试其他筛选条件',
    'review.queue.emptyTitle': '队列已清空',
    'review.queue.emptyHint': '当前没有待人工复检的录音',
    'review.queue.uncategorized': '未分类',
    'review.queue.sampledTitle': 'DFA 未命中，按抽样比例进入复检',
    'review.queue.sampledTooltip': 'DFA 未命中：按抽样比例进入复检',
    'review.queue.mine': '我的',
    'review.queue.total': '共 {total} 条',

    // 通用徽标
    'review.badges.sampled': '抽样',

    // 工作区头部
    'review.work.dfaHits': 'DFA 命中 {count} 处',
    'review.work.aiType': 'AI：{type}',
    'review.work.sampledTitle': 'DFA 未命中，按抽样比例进入 AI 语义复筛与人工复检',
    'review.work.reviewProgress': '复核 {progress}',

    // 指派
    'review.assign.assignedToMe': '指派给我',
    'review.assign.assignedTo': '指派：{name}',
    'review.assign.unassigned': '未指派',

    // 操作
    'review.actions.claim': '认领',
    'review.actions.assign': '指派',
    'review.actions.next': '下一条',
    'review.actions.nextRemaining': '（剩 {count}）',
    'review.actions.processNext': '处理下一条',

    // 已出结论
    'review.reviewed.confirmed': '已确认违规',
    'review.reviewed.falsePositive': '已判定为误报',
    'review.reviewed.confirmedSub': '结论已回馈语料库①，并触发敏感词挖掘（新词默认停用待审核）',
    'review.reviewed.falsePositiveSub': '结论已回馈语料库①，并作为合规反例参与 AI 复筛',

    // 已提交，等待下一位复核人
    'review.submitted.title': '已提交复核 {progress}',
    'review.submitted.hint':
      '本条录音需要 {required} 位复核人确认，当前已提交 {count} 人。待下一人复核后才会落最终结论并回馈语料库。',

    // 播放器
    'review.player.noAudio': '该录音无可播放文件（演示数据不含音频）',

    // 转写视图
    'review.transcript.dialogue': '双栏对话',
    'review.transcript.segment': '逐句对照',
    'review.transcript.full': '全文',
    'review.transcript.follow': '跟随播放',
    'review.transcript.empty': '（无转写文本）',

    // AI 复筛
    'review.ai.verdict': 'AI 复筛：{result}',
    'review.ai.judgedViolation': '判定违规',
    'review.ai.judgedNormal': '判定正常',
    'review.ai.confidence': '置信度',
    'review.ai.noReason': '（无复筛理由）',
    'review.ai.model': '判定模型',
    'review.ai.degraded': '降级转人工',
    'review.ai.invocations': '查看 AI 调用详情（{count} 次）',
    'review.ai.noResponse': '（无回复）',

    // 复核进度
    'review.progress.title': '复核进度',
    'review.progress.multi': '需 {required} 人确认 · 已提交 {count} 人',
    'review.progress.single': '单人复核',
    'review.progress.unit': '人已复核',

    // 提示
    'review.alerts.readOnly': '只读账号仅可查看复核信息',
    'review.alerts.sameReviewer': '双人复核：你已提交过本条录音，需另一位复核人确认',
    'review.alerts.pendingNext': '已提交 {count}/{required}，待下一人复核',

    // 复核记录
    'review.history.title': '复核记录',
    'review.history.none': '暂无',
    'review.history.empty': '尚未有人提交复核结论',
    'review.history.round': '第 {round} 轮',
    'review.history.transcript': '录音转写',

    // 只读账号
    'review.readOnly.note': '你的账号为只读角色，可查看录音与复核历史，但不能提交复核结论。',

    // 复检结论表单
    'review.form.title': '人工复检结论',
    'review.form.confirmViolation': '确认违规',
    'review.form.markNormal': '判定正常',
    'review.form.violationType': '违规类型',
    'review.form.commentPlaceholder': '复检说明（将作为语料库的标注理由，留空则沿用 AI 判定理由）',
    'review.form.sameReviewerWarn': '你已提交过本轮复核，需由另一位复核人再次确认后才能落最终结论。',
    'review.form.submitConfirmed': '确认违规并回馈语料库',
    'review.form.submitNormal': '判定正常并回馈语料库',

    // 空态
    'review.empty.selectTitle': '选择左侧录音开始复检',
    'review.empty.selectHint': '复检结论会写入语料库，用于 AI 复筛的 few-shot 参考',

    // 已复检表格
    'review.table.recording': '录音',
    'review.table.result': '结论',
    'review.table.comment': '复检备注',
    'review.table.reviewer': '复检人',
    'review.table.reviewedAt': '复检时间',
    'review.table.emptyTitle': '暂无复检记录',
    'review.table.emptyHint': '完成第一条人工复检后，这里会沉淀结论',

    // 结论文案
    'review.result.confirmed': '确认违规',
    'review.result.normal': '判定正常',
    'review.result.falsePositive': '误判放行',

    // 分声道试听
    'review.channels.mix': '混合',
    'review.channels.left': '左声道',
    'review.channels.right': '右声道',

    // 操作提示
    'review.messages.noAudioSeek': '该录音无音频文件，句子起始时间 {time}',
    'review.messages.claimed': '已认领，可开始复核',
    'review.messages.readOnlySubmit': '当前账号为只读角色，无法提交复核结论',
    'review.messages.sameReviewerSubmit': '你已提交过本轮复核，需由另一位复核人确认',
    'review.messages.selectViolationType': '请选择违规类型',
    'review.messages.submittedPending': '已提交复核 {progress}，待下一人复核',
    'review.messages.confirmedSaved': '已确认违规：结论回馈语料库，并启动敏感词挖掘',
    'review.messages.normalSaved': '已判定正常：作为合规反例回馈语料库'
  },
  en: {
    // Tabs / queue filters
    'review.tabs.pending': 'Pending review',
    'review.tabs.history': 'Reviewed',
    'review.filters.allPriorities': 'All priorities',
    'review.filters.priorityOption': '{label} priority',
    'review.filters.mineOnly': 'Mine only',

    // Review queue
    'review.queue.title': 'Review queue',
    'review.queue.hint': 'Recordings enter the queue only when both screening passes flag a violation',
    'review.queue.searchPlaceholder': 'Filter by file / transcript',
    'review.queue.searchTitle': 'Press Enter to filter by file name or transcript text',
    'review.queue.emptyFilteredTitle': 'No matching recordings',
    'review.queue.emptyFilteredHint': 'Try different filters',
    'review.queue.emptyTitle': 'Queue is empty',
    'review.queue.emptyHint': 'No recordings pending manual review',
    'review.queue.uncategorized': 'Uncategorized',
    'review.queue.sampledTitle': 'DFA missed; sampled into review by ratio',
    'review.queue.sampledTooltip': 'DFA missed: sampled into review by ratio',
    'review.queue.mine': 'Mine',
    'review.queue.total': '{total} total',

    // Shared badges
    'review.badges.sampled': 'Sampled',

    // Workspace header
    'review.work.dfaHits': 'DFA hits: {count}',
    'review.work.aiType': 'AI: {type}',
    'review.work.sampledTitle': 'DFA missed; sampled into AI screening and manual review by ratio',
    'review.work.reviewProgress': 'Reviews {progress}',

    // Assignment
    'review.assign.assignedToMe': 'Assigned to me',
    'review.assign.assignedTo': 'Assigned to {name}',
    'review.assign.unassigned': 'Unassigned',

    // Actions
    'review.actions.claim': 'Claim',
    'review.actions.assign': 'Assign',
    'review.actions.next': 'Next item',
    'review.actions.nextRemaining': ' ({count} left)',
    'review.actions.processNext': 'Process next',

    // Reviewed
    'review.reviewed.confirmed': 'Violation confirmed',
    'review.reviewed.falsePositive': 'Marked as false positive',
    'review.reviewed.confirmedSub':
      'Result fed back to the corpus① and keyword mining triggered (new keywords are disabled pending review)',
    'review.reviewed.falsePositiveSub':
      'Result fed back to the corpus① and used as a compliant counterexample in AI screening',

    // Submitted, awaiting the next reviewer
    'review.submitted.title': 'Review submitted {progress}',
    'review.submitted.hint':
      'This recording requires {required} reviewers to confirm; {count} submitted so far. The final result is set and fed back to the corpus once the next reviewer completes.',

    // Player
    'review.player.noAudio': 'No playable file for this recording (demo data contains no audio)',

    // Transcript views
    'review.transcript.dialogue': 'Dialogue',
    'review.transcript.segment': 'By segment',
    'review.transcript.full': 'Full text',
    'review.transcript.follow': 'Follow playback',
    'review.transcript.empty': '(No transcript)',

    // AI screening
    'review.ai.verdict': 'AI screening: {result}',
    'review.ai.judgedViolation': 'Violation',
    'review.ai.judgedNormal': 'No violation',
    'review.ai.confidence': 'Confidence',
    'review.ai.noReason': '(No screening reason)',
    'review.ai.model': 'Model',
    'review.ai.degraded': 'Degraded to manual',
    'review.ai.invocations': 'View AI invocations ({count})',
    'review.ai.noResponse': '(No response)',

    // Review progress
    'review.progress.title': 'Review progress',
    'review.progress.multi': '{required} reviewers required · {count} submitted',
    'review.progress.single': 'Single reviewer',
    'review.progress.unit': 'reviewers submitted',

    // Alerts
    'review.alerts.readOnly': 'Read-only accounts can only view review information',
    'review.alerts.sameReviewer':
      'Two-person review: you already submitted for this recording; another reviewer must confirm',
    'review.alerts.pendingNext': 'Submitted {count}/{required}; awaiting another reviewer',

    // Review history
    'review.history.title': 'Review history',
    'review.history.none': 'None yet',
    'review.history.empty': 'No review submitted yet',
    'review.history.round': 'Round {round}',
    'review.history.transcript': 'Recording transcript',

    // Read-only account
    'review.readOnly.note':
      'Your account has a read-only role: you can view recordings and review history, but cannot submit review results.',

    // Review verdict form
    'review.form.title': 'Manual review verdict',
    'review.form.confirmViolation': 'Confirm violation',
    'review.form.markNormal': 'Mark as normal',
    'review.form.violationType': 'Violation type',
    'review.form.commentPlaceholder':
      'Review notes (used as the corpus annotation reason; leave blank to reuse the AI reason)',
    'review.form.sameReviewerWarn':
      'You already submitted this round; another reviewer must confirm before the final result is set.',
    'review.form.submitConfirmed': 'Confirm violation and update corpus',
    'review.form.submitNormal': 'Mark as normal and update corpus',

    // Empty states
    'review.empty.selectTitle': 'Select a recording on the left to start reviewing',
    'review.empty.selectHint':
      'Review results are written to the corpus as few-shot references for AI screening',

    // Reviewed table
    'review.table.recording': 'Recording',
    'review.table.result': 'Result',
    'review.table.comment': 'Review note',
    'review.table.reviewer': 'Reviewer',
    'review.table.reviewedAt': 'Reviewed at',
    'review.table.emptyTitle': 'No review records',
    'review.table.emptyHint': 'Results will appear here after the first manual review',

    // Result labels
    'review.result.confirmed': 'Confirmed violation',
    'review.result.normal': 'No violation',
    'review.result.falsePositive': 'False positive',

    // Channel playback
    'review.channels.mix': 'Mixed',
    'review.channels.left': 'Left channel',
    'review.channels.right': 'Right channel',

    // Action messages
    'review.messages.noAudioSeek': 'This recording has no audio file; segment starts at {time}',
    'review.messages.claimed': 'Claimed; you can start reviewing',
    'review.messages.readOnlySubmit': 'Read-only accounts cannot submit review results',
    'review.messages.sameReviewerSubmit': 'You already submitted this round; another reviewer must confirm',
    'review.messages.selectViolationType': 'Please select a violation type',
    'review.messages.submittedPending': 'Review submitted {progress}; awaiting another reviewer',
    'review.messages.confirmedSaved':
      'Violation confirmed: result fed back to the corpus and keyword mining started',
    'review.messages.normalSaved':
      'Marked as normal: fed back to the corpus as a compliant counterexample'
  }
}
