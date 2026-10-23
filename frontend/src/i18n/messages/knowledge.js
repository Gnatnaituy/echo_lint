/**
 * knowledge 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `knowledge.` 前缀。
 */
export default {
  zh: {
    // 页头 / 工具栏
    'knowledge.pageTitle': '知识库 / SOP',
    'knowledge.readonly': '只读',
    'knowledge.searchPreview': '检索预览',
    'knowledge.importDocument': '导入文档',
    'knowledge.createEntry': '新建条目',
    'knowledge.searchPlaceholder': '搜索标题 / 正文',
    'knowledge.allCategories': '全部分类',
    'knowledge.allStatuses': '全部状态',
    'knowledge.query': '查询',
    'knowledge.intro.usage':
      '知识库用于沉淀话术规范、违规示例与处罚标准，供人工复核与 AI 复筛参考；',
    'knowledge.intro.import':
      '支持导入 PDF / Word / TXT / Markdown 文档并自动解析建立索引；',
    'knowledge.intro.permission': '所有登录用户可查看，管理员 / 复核员可维护。',

    // 列表
    'knowledge.title': '标题',
    'knowledge.violationType': '违规类型',
    'knowledge.tags': '标签',
    'knowledge.indexStatus': '索引状态',
    'knowledge.untitledEntry': '未命名条目',
    'knowledge.indexFailedHint': '索引失败，可点击「重建索引」重试',
    'knowledge.reindex': '重建索引',
    'knowledge.confirmDelete': '删除后不可恢复，确认删除该知识库条目？',
    'knowledge.listSeparator': '、',
    'knowledge.emptyFilteredTitle': '没有匹配的条目',
    'knowledge.emptyFilteredHint': '试试清空搜索词、分类或状态筛选',
    'knowledge.emptyTitle': '知识库还是空的',
    'knowledge.emptyCreateHint': '点击右上角「新建条目」沉淀话术规范与违规示例',
    'knowledge.emptyReadonlyHint': '暂无知识库内容',

    // 详情抽屉
    'knowledge.disabledState': '已停用',
    'knowledge.metaCreated': '创建：{value}',
    'knowledge.metaUpdated': '更新：{value}',
    'knowledge.indexInfo': '索引信息',
    'knowledge.source': '来源',
    'knowledge.sourceDocument': '文档导入',
    'knowledge.sourceManual': '手工录入',
    'knowledge.sourceFile': '来源文件',
    'knowledge.fileSize': '文件大小',
    'knowledge.chunkCount': '分块数',
    'knowledge.indexedAt': '索引时间',
    'knowledge.content': '正文',
    'knowledge.noContent': '（暂无正文）',

    // 新建 / 编辑弹窗
    'knowledge.editTitle': '编辑知识库条目',
    'knowledge.createTitle': '新建知识库条目',
    'knowledge.titlePlaceholder': '如：催收话术红线',
    'knowledge.categoryEditPlaceholder': '选择或输入分类',
    'knowledge.violationTypePlaceholder': '可选，关联敏感词违规类型',
    'knowledge.tagsPlaceholder': '输入标签后回车添加',
    'knowledge.contentPlaceholder':
      '支持 Markdown / 纯文本，可记录话术正反例、判定标准与处置建议',
    'knowledge.enabledHint': '停用后仍保留在列表中，供管理员后续维护',
    'knowledge.saveEdit': '保存修改',
    'knowledge.createSubmit': '创建条目',

    // 导入文档弹窗
    'knowledge.importTitle': '导入知识库文档',
    'knowledge.uploadDrag': '将文档拖到此处，或 ',
    'knowledge.uploadClick': '点击选择',
    'knowledge.uploadTip':
      '支持 PDF / DOCX / TXT / Markdown，单文件不超过 20 MB；上传后后台解析并建立索引。',
    'knowledge.importTitlePlaceholder': '可选，留空使用文件名',
    'knowledge.importCategoryPlaceholder': '可选，选择或输入分类',
    'knowledge.importing': '正在上传文档，请勿关闭对话框…',
    'knowledge.uploading': '上传中…',
    'knowledge.startImport': '开始导入',

    // 检索预览弹窗
    'knowledge.searchTitle': '知识库检索预览',
    'knowledge.searchQueryPlaceholder': '输入问题或关键词，查看语义检索命中的知识片段',
    'knowledge.searchButton': '检索',
    'knowledge.topKHint': 'Top K 为召回条数（1~10），检索使用知识库已就绪的向量片段。',
    'knowledge.searching': '正在检索…',
    'knowledge.searchEmptyTitle': '没有召回相关知识片段',
    'knowledge.searchEmptyHint': '试试换一个说法，或确认 RAG 已启用且知识库索引已就绪',
    'knowledge.similarity': '相似度 {score}',
    'knowledge.noSnippet': '（无内容）',
    'knowledge.searchIdleHint': '输入查询后点击「检索」查看召回结果。',

    // 操作提示 / 错误兜底
    'knowledge.errorForbidden': '当前账号无权限查看知识库（请重新登录）',
    'knowledge.errorNotFound': '后端尚未提供知识库接口（HTTP 404），请确认后端版本',
    'knowledge.errorLoadFailed': '知识库加载失败，请稍后重试',
    'knowledge.searchErrorForbidden': '当前账号无检索权限（需要 ADMIN / REVIEWER）',
    'knowledge.searchErrorNotFound': '后端尚未提供语义检索接口（HTTP 404），请确认后端版本',
    'knowledge.searchErrorFailed': '检索失败，请稍后重试',
    'knowledge.titleRequired': '请输入标题',
    'knowledge.titleMax': '标题最多 200 个字符',
    'knowledge.updated': '知识库条目已更新',
    'knowledge.created': '知识库条目已创建',
    'knowledge.deleted': '已删除「{title}」',
    'knowledge.reindexQueued': '「{title}」已提交重建索引，后台处理中',
    'knowledge.uploadUnsupported': '仅支持 PDF / DOCX / TXT / MD 文档',
    'knowledge.uploadTooLarge': '文档超过 20 MB，请压缩或拆分后再导入',
    'knowledge.uploadSingleFile': '一次只能导入一份文档，请先移除已选择的文件',
    'knowledge.uploadNoFile': '请先选择要导入的文档',
    'knowledge.imported': '「{title}」已导入，后台正在解析并建立索引',
    'knowledge.documentFallback': '文档',
    'knowledge.searchQueryRequired': '请输入检索关键词',

    // 分类候选的展示文案（值本身是持久化中文，见 utils/knowledge.js）
    'knowledge.category.script': '话术规范',
    'knowledge.category.violationExample': '违规示例',
    'knowledge.category.penalty': '处罚标准',
    'knowledge.category.sop': 'SOP',
    'knowledge.category.other': '其他'
  },
  en: {
    // Header / toolbar
    'knowledge.pageTitle': 'Knowledge Base / SOP',
    'knowledge.readonly': 'Read-only',
    'knowledge.searchPreview': 'Search preview',
    'knowledge.importDocument': 'Import document',
    'knowledge.createEntry': 'New entry',
    'knowledge.searchPlaceholder': 'Search title / content',
    'knowledge.allCategories': 'All categories',
    'knowledge.allStatuses': 'All statuses',
    'knowledge.query': 'Search',
    'knowledge.intro.usage':
      'The knowledge base captures script guidelines, violation examples and penalty standards as references for manual review and AI screening;',
    'knowledge.intro.import':
      'Import PDF / Word / TXT / Markdown documents and index them automatically after parsing;',
    'knowledge.intro.permission':
      'Visible to all signed-in users; administrators and reviewers can maintain it.',

    // List
    'knowledge.title': 'Title',
    'knowledge.violationType': 'Violation type',
    'knowledge.tags': 'Tags',
    'knowledge.indexStatus': 'Index status',
    'knowledge.untitledEntry': 'Untitled entry',
    'knowledge.indexFailedHint': 'Indexing failed. Click "Rebuild index" to retry',
    'knowledge.reindex': 'Rebuild index',
    'knowledge.confirmDelete':
      'Deletion cannot be undone. Delete this knowledge entry?',
    'knowledge.listSeparator': ', ',
    'knowledge.emptyFilteredTitle': 'No matching entries',
    'knowledge.emptyFilteredHint': 'Try clearing the keyword, category or status filters',
    'knowledge.emptyTitle': 'The knowledge base is empty',
    'knowledge.emptyCreateHint':
      'Click "New entry" in the top right to add script guidelines and violation examples',
    'knowledge.emptyReadonlyHint': 'No knowledge base content yet',

    // Detail drawer
    'knowledge.disabledState': 'Disabled',
    'knowledge.metaCreated': 'Created: {value}',
    'knowledge.metaUpdated': 'Updated: {value}',
    'knowledge.indexInfo': 'Index information',
    'knowledge.source': 'Source',
    'knowledge.sourceDocument': 'Document import',
    'knowledge.sourceManual': 'Manual entry',
    'knowledge.sourceFile': 'Source file',
    'knowledge.fileSize': 'File size',
    'knowledge.chunkCount': 'Chunk count',
    'knowledge.indexedAt': 'Indexed at',
    'knowledge.content': 'Content',
    'knowledge.noContent': '(No content)',

    // Create / edit dialog
    'knowledge.editTitle': 'Edit Knowledge Entry',
    'knowledge.createTitle': 'New Knowledge Entry',
    'knowledge.titlePlaceholder': 'e.g. Collections script red lines',
    'knowledge.categoryEditPlaceholder': 'Select or enter a category',
    'knowledge.violationTypePlaceholder': 'Optional. Link to a sensitive word violation type',
    'knowledge.tagsPlaceholder': 'Type a tag and press Enter',
    'knowledge.contentPlaceholder':
      'Markdown or plain text. Record good and bad script examples, criteria and handling advice',
    'knowledge.enabledHint':
      'Disabled entries stay in the list for later maintenance by administrators',
    'knowledge.saveEdit': 'Save changes',
    'knowledge.createSubmit': 'Create entry',

    // Import document dialog
    'knowledge.importTitle': 'Import Knowledge Document',
    'knowledge.uploadDrag': 'Drag the document here, or ',
    'knowledge.uploadClick': 'click to select',
    'knowledge.uploadTip':
      'Supports PDF / DOCX / TXT / Markdown, up to 20 MB per file. The document is parsed and indexed in the background after upload.',
    'knowledge.importTitlePlaceholder': 'Optional. Defaults to the file name',
    'knowledge.importCategoryPlaceholder': 'Optional. Select or enter a category',
    'knowledge.importing': 'Uploading the document. Please keep this dialog open…',
    'knowledge.uploading': 'Uploading…',
    'knowledge.startImport': 'Start import',

    // Search preview dialog
    'knowledge.searchTitle': 'Knowledge Search Preview',
    'knowledge.searchQueryPlaceholder':
      'Enter a question or keyword to preview matching semantic search snippets',
    'knowledge.searchButton': 'Search',
    'knowledge.topKHint':
      'Top K is the number of recalled chunks (1–10); search uses knowledge chunks that are ready.',
    'knowledge.searching': 'Searching…',
    'knowledge.searchEmptyTitle': 'No relevant snippets recalled',
    'knowledge.searchEmptyHint':
      'Try rephrasing, or check that RAG is enabled and the knowledge index is ready',
    'knowledge.similarity': 'Similarity {score}',
    'knowledge.noSnippet': '(No content)',
    'knowledge.searchIdleHint': 'Enter a query and click "Search" to see results.',

    // Operation messages / error fallbacks
    'knowledge.errorForbidden':
      'Your account does not have permission to view the knowledge base (please sign in again)',
    'knowledge.errorNotFound':
      'The backend does not provide the knowledge base API yet (HTTP 404). Please check the backend version',
    'knowledge.errorLoadFailed': 'Failed to load the knowledge base. Please try again later',
    'knowledge.searchErrorForbidden':
      'Your account does not have search permission (ADMIN / REVIEWER required)',
    'knowledge.searchErrorNotFound':
      'The backend does not provide the semantic search API yet (HTTP 404). Please check the backend version',
    'knowledge.searchErrorFailed': 'Search failed. Please try again later',
    'knowledge.titleRequired': 'Please enter a title',
    'knowledge.titleMax': 'Title must be 200 characters or fewer',
    'knowledge.updated': 'Knowledge entry updated',
    'knowledge.created': 'Knowledge entry created',
    'knowledge.deleted': 'Deleted "{title}"',
    'knowledge.reindexQueued': 'Rebuild requested for "{title}"; processing in the background',
    'knowledge.uploadUnsupported': 'Only PDF / DOCX / TXT / MD documents are supported',
    'knowledge.uploadTooLarge':
      'The document exceeds 20 MB. Compress or split it before importing',
    'knowledge.uploadSingleFile':
      'Only one document can be imported at a time. Remove the selected file first',
    'knowledge.uploadNoFile': 'Please select a document to import',
    'knowledge.imported': '"{title}" imported; parsing and indexing in the background',
    'knowledge.documentFallback': 'Document',
    'knowledge.searchQueryRequired': 'Please enter a search keyword',

    // Category display labels (the stored values themselves stay in Chinese)
    'knowledge.category.script': 'Script guidelines',
    'knowledge.category.violationExample': 'Violation examples',
    'knowledge.category.penalty': 'Penalty standards',
    'knowledge.category.sop': 'SOP',
    'knowledge.category.other': 'Other'
  }
}
