/**
 * dictionary 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `dictionary.` 前缀。
 */
export default {
  zh: {
    // 概览
    'dictionary.kpi.total': '词条总数',
    'dictionary.kpi.enabled': '启用中',
    'dictionary.kpi.enabledSub': '启用中 {count} 条',
    'dictionary.kpi.enabledHint': '参与 DFA 初筛',
    'dictionary.kpi.minedPending': 'AI 挖掘待审核',
    'dictionary.kpi.minedPendingHint': '来自确认违规录音',

    // DFA 检测工具
    'dictionary.dfa.title': 'DFA 检测工具',
    'dictionary.dfa.subtitle': '使用当前启用词典实时匹配',
    'dictionary.dfa.placeholder': '粘贴一段转写文本，例如：you are a f***ing idiot, I will kill you!',
    'dictionary.dfa.test': '检测',
    'dictionary.dfa.clear': '清空',
    'dictionary.dfa.hitCount': '命中 {count} 处',
    'dictionary.dfa.noHit': '未命中，文本干净',
    'dictionary.dfa.preview': '命中预览',
    'dictionary.dfa.readonlyHint':
      '只读角色可浏览词库与统计；DFA 检测工具与词条维护仅管理员可用。',

    // 匹配规则与维护说明
    'dictionary.rules.title': '匹配规则与维护说明',
    'dictionary.rules.caseInsensitive': '不区分大小写',
    'dictionary.rules.caseInsensitiveDesc': '，且忽略词间空格与标点：词典里的 ',
    'dictionary.rules.caseInsensitiveMatches': ' 能命中 ',
    'dictionary.rules.listSeparator': '、',
    'dictionary.rules.sentenceEnd': '。',
    'dictionary.rules.substring': '子串语义',
    'dictionary.rules.substringLead': '：',
    'dictionary.rules.substringMatches': ' 会命中 ',
    'dictionary.rules.substringTail': '。命中的误报由 AI 语义复筛兜底，这是两段式设计的取舍。',
    'dictionary.rules.minedDisabled': 'AI 挖掘的词默认停用',
    'dictionary.rules.minedDisabledDesc':
      '：人工复检确认违规后，系统会提炼新词入库，需在此页审核后启用。',
    'dictionary.rules.saveTakesEffect': '保存即生效',
    'dictionary.rules.saveTakesEffectDesc': '：任何增删改都会重建 DFA 匹配器，无需重启服务。',

    // 工具栏
    'dictionary.filters.disabled': '已停用',
    'dictionary.readonly': '只读模式',
    'dictionary.searchPlaceholder': '搜索词条',
    'dictionary.exportCsv': '导出 CSV',
    'dictionary.importCsv': '导入 CSV',
    'dictionary.createEntry': '新增词条',

    // 批量启停
    'dictionary.bulk.selected': '已选 {count} 条',
    'dictionary.bulk.enable': '批量启用',
    'dictionary.bulk.disable': '批量停用',
    'dictionary.bulk.clear': '取消选择',
    'dictionary.bulk.hint': '启停后立即重建 DFA 匹配器',
    'dictionary.bulk.confirmDisable': '确认停用选中词条？停用后不再参与 DFA 初筛。',

    // 词条表
    'dictionary.table.word': '敏感词',
    'dictionary.table.category': '违规类型',
    'dictionary.table.severity': '严重度',
    'dictionary.table.source': '来源',
    'dictionary.table.hits': '命中次数',
    'dictionary.table.enabled': '启用',
    'dictionary.pendingReview': '待审核',
    'dictionary.pendingReviewTip': 'AI 从确认违规录音中挖掘，启用前请人工审核',
    'dictionary.confirmDelete': '确认删除该词？',
    'dictionary.empty.title': '没有匹配的词条',
    'dictionary.empty.hint': '调整筛选条件，或新增一个敏感词',

    // 导入 CSV
    'dictionary.import.title': '导入敏感词 CSV',
    'dictionary.import.columnsLead': 'CSV 列：',
    'dictionary.import.columnsRest': '，允许表头，最多 5000 行；规范化后自动去重。',
    'dictionary.import.valueSyntax':
      'category：INSULT / DISCRIMINATION / THREAT / HARASSMENT / SEXUAL / FRAUD / PRIVACY / OTHER；severity：HIGH / MEDIUM / LOW；enabled：true / false。',
    'dictionary.import.dragPrefix': '拖拽 CSV 到此处，或 ',
    'dictionary.import.clickSelect': '点击选择',
    'dictionary.import.uploadTip': '单文件 ≤ 5MB；导入成功后自动重建 DFA 匹配器',
    'dictionary.import.created': '新增',
    'dictionary.import.skipped': '跳过（重复/已存在）',
    'dictionary.import.invalid': '格式错误',
    'dictionary.import.done': '完成',
    'dictionary.import.start': '开始导入',
    'dictionary.import.continue': '继续导入',

    // 新增 / 编辑敏感词
    'dictionary.dialog.createTitle': '新增敏感词',
    'dictionary.dialog.editTitle': '编辑敏感词',
    'dictionary.form.wordPlaceholder': '如 kill you / 傻逼',
    'dictionary.form.normalized': '规范化结果：',
    'dictionary.form.normalizedHint': '（小写、去空格与标点）',
    'dictionary.form.enabledHint': '停用的词不参与初筛',
    'dictionary.form.saveAndRebuild': '保存并重建匹配器',

    // 操作反馈
    'dictionary.messages.wordRequired': '请输入敏感词',
    'dictionary.messages.updated': '已更新，DFA 匹配器已重建',
    'dictionary.messages.created': '已新增，DFA 匹配器已重建',
    'dictionary.messages.enabledWord': '已启用「{word}」',
    'dictionary.messages.disabledWord': '已停用「{word}」',
    'dictionary.messages.deleted': '已删除',
    'dictionary.messages.bulkEnabled': '已启用 {count} 条，DFA 匹配器已重建',
    'dictionary.messages.bulkDisabled': '已停用 {count} 条，DFA 匹配器已重建',
    'dictionary.messages.exported': '词库已导出：{filename}',
    'dictionary.messages.csvRequired': '请选择 .csv 文件',
    'dictionary.messages.csvTooLarge': 'CSV 文件超过 5MB',
    'dictionary.messages.importFileRequired': '请选择要导入的 CSV 文件',
    'dictionary.messages.importDone': '导入完成：新增 {created}，跳过 {skipped}，无效 {invalid}',
    'dictionary.messages.testTextRequired': '请输入检测文本'
  },
  en: {
    // Overview
    'dictionary.kpi.total': 'Total entries',
    'dictionary.kpi.enabled': 'Enabled',
    'dictionary.kpi.enabledSub': '{count} enabled',
    'dictionary.kpi.enabledHint': 'Used in DFA screening',
    'dictionary.kpi.minedPending': 'AI-mined pending review',
    'dictionary.kpi.minedPendingHint': 'From confirmed violations',

    // DFA test tool
    'dictionary.dfa.title': 'DFA test tool',
    'dictionary.dfa.subtitle': 'Real-time matching against the enabled dictionary',
    'dictionary.dfa.placeholder': 'Paste a transcript, e.g. you are a f***ing idiot, I will kill you!',
    'dictionary.dfa.test': 'Test',
    'dictionary.dfa.clear': 'Clear',
    'dictionary.dfa.hitCount': 'Matches: {count}',
    'dictionary.dfa.noHit': 'No matches. The text is clean.',
    'dictionary.dfa.preview': 'Match preview',
    'dictionary.dfa.readonlyHint':
      'Read-only users can browse the dictionary and statistics; the DFA test tool and entry management are available to administrators only.',

    // Matching rules and maintenance
    'dictionary.rules.title': 'Matching rules and maintenance',
    'dictionary.rules.caseInsensitive': 'Case-insensitive',
    'dictionary.rules.caseInsensitiveDesc':
      ', and ignores spaces and punctuation between characters: the entry ',
    'dictionary.rules.caseInsensitiveMatches': ' also matches ',
    'dictionary.rules.listSeparator': ' and ',
    'dictionary.rules.sentenceEnd': '.',
    'dictionary.rules.substring': 'Substring matching',
    'dictionary.rules.substringLead': ': ',
    'dictionary.rules.substringMatches': ' matches ',
    'dictionary.rules.substringTail':
      '. False positives are caught by AI semantic screening — the trade-off of the two-stage design.',
    'dictionary.rules.minedDisabled': 'AI-mined words are disabled by default',
    'dictionary.rules.minedDisabledDesc':
      ': after a reviewer confirms a violation, the system extracts new words into the dictionary; review and enable them on this page.',
    'dictionary.rules.saveTakesEffect': 'Changes take effect immediately',
    'dictionary.rules.saveTakesEffectDesc':
      ': every create, update or delete rebuilds the DFA matcher; no service restart is needed.',

    // Toolbar
    'dictionary.filters.disabled': 'Disabled',
    'dictionary.readonly': 'Read-only',
    'dictionary.searchPlaceholder': 'Search entries',
    'dictionary.exportCsv': 'Export CSV',
    'dictionary.importCsv': 'Import CSV',
    'dictionary.createEntry': 'New entry',

    // Bulk enable / disable
    'dictionary.bulk.selected': '{count} selected',
    'dictionary.bulk.enable': 'Enable selected',
    'dictionary.bulk.disable': 'Disable selected',
    'dictionary.bulk.clear': 'Clear selection',
    'dictionary.bulk.hint': 'Enabling or disabling rebuilds the DFA matcher immediately',
    'dictionary.bulk.confirmDisable':
      'Disable the selected entries? They will no longer participate in DFA screening.',

    // Entries table
    'dictionary.table.word': 'Sensitive word',
    'dictionary.table.category': 'Violation type',
    'dictionary.table.severity': 'Severity',
    'dictionary.table.source': 'Source',
    'dictionary.table.hits': 'Hits',
    'dictionary.table.enabled': 'Enabled',
    'dictionary.pendingReview': 'Pending review',
    'dictionary.pendingReviewTip':
      'Mined by AI from confirmed violations; review manually before enabling',
    'dictionary.confirmDelete': 'Delete this word?',
    'dictionary.empty.title': 'No matching entries',
    'dictionary.empty.hint': 'Adjust the filters or add a sensitive word',

    // Import CSV
    'dictionary.import.title': 'Import Sensitive Words CSV',
    'dictionary.import.columnsLead': 'CSV columns: ',
    'dictionary.import.columnsRest':
      '; the header row is optional, up to 5,000 rows; duplicates are removed after normalization.',
    'dictionary.import.valueSyntax':
      'category: INSULT / DISCRIMINATION / THREAT / HARASSMENT / SEXUAL / FRAUD / PRIVACY / OTHER; severity: HIGH / MEDIUM / LOW; enabled: true / false.',
    'dictionary.import.dragPrefix': 'Drag the CSV here, or ',
    'dictionary.import.clickSelect': 'click to select',
    'dictionary.import.uploadTip':
      'Single file ≤ 5 MB; the DFA matcher is rebuilt after a successful import',
    'dictionary.import.created': 'Created',
    'dictionary.import.skipped': 'Skipped (duplicate)',
    'dictionary.import.invalid': 'Invalid format',
    'dictionary.import.done': 'Done',
    'dictionary.import.start': 'Start import',
    'dictionary.import.continue': 'Import more',

    // Create / edit sensitive word
    'dictionary.dialog.createTitle': 'New Sensitive Word',
    'dictionary.dialog.editTitle': 'Edit Sensitive Word',
    'dictionary.form.wordPlaceholder': 'e.g. kill you / idiot',
    'dictionary.form.normalized': 'Normalized: ',
    'dictionary.form.normalizedHint': '(lowercase; spaces and punctuation removed)',
    'dictionary.form.enabledHint': 'Disabled words are not screened',
    'dictionary.form.saveAndRebuild': 'Save and rebuild matcher',

    // Feedback messages
    'dictionary.messages.wordRequired': 'Please enter a sensitive word',
    'dictionary.messages.updated': 'Updated. The DFA matcher has been rebuilt.',
    'dictionary.messages.created': 'Created. The DFA matcher has been rebuilt.',
    'dictionary.messages.enabledWord': 'Enabled "{word}"',
    'dictionary.messages.disabledWord': 'Disabled "{word}"',
    'dictionary.messages.deleted': 'Deleted',
    'dictionary.messages.bulkEnabled': 'Enabled entries: {count}. The DFA matcher has been rebuilt.',
    'dictionary.messages.bulkDisabled': 'Disabled entries: {count}. The DFA matcher has been rebuilt.',
    'dictionary.messages.exported': 'Dictionary exported: {filename}',
    'dictionary.messages.csvRequired': 'Please select a .csv file',
    'dictionary.messages.csvTooLarge': 'CSV file exceeds 5 MB',
    'dictionary.messages.importFileRequired': 'Please select a CSV file to import',
    'dictionary.messages.importDone':
      'Import complete: {created} created, {skipped} skipped, {invalid} invalid',
    'dictionary.messages.testTextRequired': 'Please enter text to test'
  }
}
