/**
 * components 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `components.` 前缀。
 * 覆盖共享组件：音频播放器 / 指派弹窗 / 逐句转写 / 全文转写，以及上传队列工具的展示兜底。
 * 带数量的文案英文区分单复数时，成对提供 `.one` / `.other` 两条 key。
 */
export default {
  zh: {
    // 音频播放器
    'components.audioPlayer.play': '播放',
    'components.audioPlayer.pause': '暂停',
    'components.audioPlayer.seekProgress': '播放进度',

    // 指派复核人
    'components.assign.title': '指派复核人',
    'components.assign.recordingFallback': '录音 #{id}',
    'components.assign.reviewer': '复核人',
    'components.assign.reviewerPlaceholder': '选择复核员，或直接输入用户名',
    'components.assign.hint': '指派后该录音会出现在对方的复检队列；仅管理员可指派。',
    'components.assign.submit': '指派',
    'components.assign.usersLoadFailed': '用户列表加载失败，可直接输入用户名指派',
    'components.assign.success': '已指派给 {name}',
    'components.assign.optionLabel': '{name}（{username}）',

    // 逐句转写
    'components.transcript.empty': '（无转写文本）',
    'components.transcript.sentenceCount.one': '{count} 句',
    'components.transcript.sentenceCount.other': '{count} 句',
    'components.transcript.hitCount.one': '{count} 处命中',
    'components.transcript.hitCount.other': '{count} 处命中',
    'components.transcript.markedCount.one': '{count} 句有标记',
    'components.transcript.markedCount.other': '{count} 句有标记',
    'components.transcript.untimed': '无分段时长，点击跳转不可用',
    'components.transcript.seekHint': '点击任意句子跳转到对应音频位置',
    'components.transcript.channelLeft': '左声道',
    'components.transcript.channelRight': '右声道',
    'components.transcript.untimedTitle': '该句无时间轴',
    'components.transcript.seekTo': '跳转到 {time}',
    'components.transcript.hitChip': '命中 · {word}',
    'components.transcript.targetChip': 'AI 判违规原句',

    // 全文转写
    'components.transcriptViewer.targetLabel': 'AI 判定违规原句',
    'components.transcriptViewer.hitCount.one': 'DFA 命中 {count} 处',
    'components.transcriptViewer.hitCount.other': 'DFA 命中 {count} 处',

    // 批量上传队列
    'components.upload.fileFallback': '文件{index}'
  },
  en: {
    // Audio player
    'components.audioPlayer.play': 'Play',
    'components.audioPlayer.pause': 'Pause',
    'components.audioPlayer.seekProgress': 'Playback progress',

    // Assign reviewer
    'components.assign.title': 'Assign Reviewer',
    'components.assign.recordingFallback': 'Recording #{id}',
    'components.assign.reviewer': 'Reviewer',
    'components.assign.reviewerPlaceholder': 'Select a reviewer or type a username',
    'components.assign.hint':
      "Once assigned, the recording appears in the reviewer's review queue. Only administrators can assign.",
    'components.assign.submit': 'Assign',
    'components.assign.usersLoadFailed': 'Failed to load users. Type a username to assign directly.',
    'components.assign.success': 'Assigned to {name}',
    'components.assign.optionLabel': '{name} ({username})',

    // Sentence transcript
    'components.transcript.empty': '(No transcript)',
    'components.transcript.sentenceCount.one': '{count} sentence',
    'components.transcript.sentenceCount.other': '{count} sentences',
    'components.transcript.hitCount.one': '{count} hit',
    'components.transcript.hitCount.other': '{count} hits',
    'components.transcript.markedCount.one': '{count} sentence marked',
    'components.transcript.markedCount.other': '{count} sentences marked',
    'components.transcript.untimed': 'No segment timings; click-to-seek unavailable',
    'components.transcript.seekHint': 'Click any sentence to jump to that point in the audio',
    'components.transcript.channelLeft': 'Left channel',
    'components.transcript.channelRight': 'Right channel',
    'components.transcript.untimedTitle': 'No timeline for this sentence',
    'components.transcript.seekTo': 'Jump to {time}',
    'components.transcript.hitChip': 'Hit · {word}',
    'components.transcript.targetChip': 'AI-flagged sentence',

    // Full transcript
    'components.transcriptViewer.targetLabel': 'AI-flagged sentence',
    'components.transcriptViewer.hitCount.one': '{count} DFA hit',
    'components.transcriptViewer.hitCount.other': '{count} DFA hits',

    // Upload queue
    'components.upload.fileFallback': 'File {index}'
  }
}
