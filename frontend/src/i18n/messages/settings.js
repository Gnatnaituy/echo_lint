/**
 * settings 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `settings.` 前缀。
 * 分组按页面区块顺序：active（当前生效）/ model（切换）/ provider（供应商注册表）/
 * ab（A/B 与评测）/ rag（检索增强）/ scope（生效范围）/ notify（抽样与通知）/ state / error。
 */
export default {
  zh: {
    // 通用状态胶囊
    'settings.state.enabled': '已启用',
    'settings.state.disabled': '未启用',
    'settings.state.configured': '已配置',
    'settings.state.notConfigured': '未配置',
    'settings.state.available': '可用',
    'settings.state.unavailable': '不可用',
    'settings.state.urlMissing': '待配置 URL',

    // 配置来源
    'settings.source.configDefault': '配置默认',
    'settings.source.uiSwitch': '界面切换',
    'settings.source.uiSaved': '界面保存',

    // ① 当前生效模型
    'settings.active.title': '当前 AI 复筛模型',
    'settings.active.subtitle': '作用于语义复筛与敏感词挖掘',
    'settings.active.keyConfigured': 'API Key 已配置，可正常调用',
    'settings.active.keyMissing':
      'API Key 未配置：DFA 命中的录音会降级转人工复检，不会漏审',
    'settings.active.testConnection': '测试连通性',
    'settings.active.switched': '已切换到 {provider} · {model}',

    // 连通性测试结果（模型 / 供应商共用）
    'settings.test.ok': '连通正常',
    'settings.test.fail': '连通失败',
    'settings.test.latency': '耗时 {ms} ms',
    'settings.test.emptyReply': '（无返回内容）',

    // ② 切换模型
    'settings.model.title': '切换模型',
    'settings.model.subtitle': '保存后立即生效，无需重启；选择会持久化保存',
    'settings.model.providerDisabled': '该供应商已在「AI 供应商」中停用，启用后才能切换',
    'settings.model.keyConfigured': 'Key 已配置',
    'settings.model.keyMissingEnv': '缺少 {env}',
    'settings.model.keyMissing': 'Key 未配置',
    'settings.model.label': '模型',
    'settings.model.placeholder': '选择或直接输入模型名',
    'settings.model.test': '测试',
    'settings.model.save': '保存并生效',
    'settings.model.hintSelect':
      '可下拉选择，也可直接输入候选之外的新模型名（供应商上新模型时无需改配置重启）。',
    'settings.model.hintEffect':
      '切换立即影响之后的所有复筛与词挖掘，已有结论的历史录音不会被重新判定。',
    'settings.model.readonlyAlert':
      '只读角色可查看当前生效模型；切换模型与连通性测试仅管理员可用。',

    // ③ AI 供应商注册表
    'settings.provider.title': 'AI 供应商',
    'settings.provider.subtitle': '内置供应商由后端配置首次启动落库；密钥加密存储，仅显示掩码',
    'settings.provider.create': '新增供应商',
    'settings.provider.editTitle': '编辑供应商',
    'settings.provider.current': '当前',
    'settings.provider.builtIn': '内置',
    'settings.provider.unavailable': '供应商列表暂不可用',
    'settings.provider.columnName': '供应商',
    'settings.provider.baseUrl': '接入地址',
    'settings.provider.defaultChatPath': '默认 chatPath',
    'settings.provider.defaultModel': '默认模型',
    'settings.provider.modelCount': '{count} 个候选模型',
    'settings.provider.pricePer1k': '价格 / 1K',
    'settings.provider.priceTooltip': '单位 USD / 1K tokens；入 = 输入价格，出 = 输出价格',
    'settings.provider.priceIn': '入',
    'settings.provider.priceOut': '出',
    'settings.provider.keyColumn': '密钥',
    'settings.provider.test': '测试',
    'settings.provider.activeDeleteTooltip': '当前生效供应商不可删除，请先切换模型',
    'settings.provider.deleteConfirm': '删除后该供应商配置与密钥将被移除（不可恢复），确认删除？',
    'settings.provider.empty': '暂无供应商',
    'settings.provider.emptyHint':
      '内置供应商会在后端首次启动时自动落库；也可点击右上角「新增供应商」接入自定义网关',
    'settings.provider.hintA': '内置供应商可编辑接入信息、价格与密钥，但不可删除；自定义供应商可增删。',
    'settings.provider.hintB': '停用或删除不会影响历史录音结论与已产生的调用留痕。',
    'settings.provider.loadFailed': '供应商列表加载失败，请稍后重试',
    'settings.provider.testFailed': '测试请求失败，请检查网关与后端日志',
    'settings.provider.updated': '供应商 {label} 已更新',
    'settings.provider.created': '供应商 {label} 已创建',
    'settings.provider.deleted': '供应商 {label} 已删除',
    'settings.provider.saveEdit': '保存修改',
    'settings.provider.createSubmit': '创建供应商',
    'settings.provider.formKey': '唯一标识',
    'settings.provider.formKeyPlaceholder': '如 my-gateway（小写字母 / 数字 / . _ -）',
    'settings.provider.formLabelPlaceholder': '展示名称，如 自建网关',
    'settings.provider.formDefaultModelPlaceholder': '如 gpt-4o-mini',
    'settings.provider.formModels': '模型列表',
    'settings.provider.formModelsPlaceholder': '输入模型名后回车添加',
    'settings.provider.formModelsHint': '用于切换模型下拉与连通性测试；默认模型会自动包含。',
    'settings.provider.formInputPrice': '输入',
    'settings.provider.formOutputPrice': '输出',
    'settings.provider.formPriceHint':
      '单位 USD / 1K tokens，调用时按此单价快照计算成本；0 表示不参与费用统计。',
    'settings.provider.formMaxTokens': '最大输出 Tokens',
    'settings.provider.formMaxTokensPlaceholder': '留空使用供应商默认',
    'settings.provider.formJsonMode': 'JSON 模式',
    'settings.provider.formJsonModeHint': '要求模型返回 JSON 对象',
    'settings.provider.formEnabled': '启用',
    'settings.provider.formEnabledHint': '停用后不可切换为当前模型',
    'settings.provider.formApiKeyKeep': '留空不修改（当前：{masked}）',
    'settings.provider.formApiKeyOptional': '可留空，稍后补充',
    'settings.provider.formApiKeyHint': '仅提交给后端加密存储，接口与前端永远只回掩码。',
    'settings.provider.formBuiltInAlert':
      '内置供应商可编辑接入信息、价格与密钥，但不可删除；密钥留空表示保持不变。',
    'settings.provider.ruleLabelRequired': '请输入名称',
    'settings.provider.ruleBaseUrlRequired': '请输入 Base URL',
    'settings.provider.ruleBaseUrlPattern': '需为 http(s):// 开头的地址',
    'settings.provider.ruleDefaultModelRequired': '请输入默认模型',
    'settings.provider.ruleKeyRequired': '请输入唯一标识',
    'settings.provider.ruleKeyPattern': '1-64 位小写字母 / 数字 / . _ -，以字母或数字开头',

    // 供应商连通性测试弹窗
    'settings.providerTest.title': '测试供应商连通性',
    'settings.providerTest.disabledChip': '已停用',
    'settings.providerTest.model': '测试模型',
    'settings.providerTest.modelPlaceholder': '留空使用默认模型：{model}',
    'settings.providerTest.failHint': '请检查 Base URL / 密钥 / 模型名',
    'settings.providerTest.send': '发送测试请求',

    // ④ A/B 分流
    'settings.ab.title': 'A/B 与模型评测',
    'settings.ab.subtitle': 'A/B 配置只读展示；评测为手动触发，单次最多 20 条语料',
    'settings.ab.split': 'A/B 分流',
    'settings.ab.percent': 'B 组流量占比',
    'settings.ab.percentNote': '按概率进入 B 组；关闭或备用供应商不可用时回退当前生效模型',
    'settings.ab.group': 'B 组供应商 / 模型',
    'settings.ab.providerDefault': '供应商默认',
    'settings.ab.groupNote': '仅语义复筛参与分流；敏感词挖掘始终使用当前生效模型',
    'settings.ab.missing': '接口未返回 A/B 配置（后端可能为旧版本，或 A/B 未配置）',
    'settings.ab.pending': 'A/B 配置随「当前 AI 复筛模型」加载，暂不可用',
    'settings.ab.stateNotConfigured': '未配置',
    'settings.ab.stateUnknown': '状态未知',
    'settings.ab.noteNoConfig': '接口未返回 A/B 配置，后端可能为旧版本',
    'settings.ab.noteDisabled': 'A/B 分流已关闭，全部流量走当前生效模型',
    'settings.ab.notePercentMissing': '已开启，但接口未返回流量占比',
    'settings.ab.notePercent': '约 {percent} 的语义复筛流量进入 B 组',
    'settings.ab.noteUnknown': '接口未返回启用状态',

    // ④ 模型评测
    'settings.eval.provider': '供应商',
    'settings.eval.providerPlaceholder': '选择供应商',
    'settings.eval.modelLabel': '模型（可选）',
    'settings.eval.modelPlaceholder': '留空使用供应商默认模型',
    'settings.eval.sampleSize': '样本数',
    'settings.eval.run': '运行评测',
    'settings.eval.providerUnavailable':
      '供应商列表不可用：请前往上方「AI 供应商」检查配置',
    'settings.eval.running': '正在逐条调用模型评测（1~20 条，可能需要数十秒）…',
    'settings.eval.incomplete': '评测未完成',
    'settings.eval.evaluatedRatio': '已评测 / 总数',
    'settings.eval.correctFailed': '正确 {correct} · 失败 {failed}',
    'settings.eval.total': '总计 {total}',
    'settings.eval.latency': '耗时 {value}',
    'settings.eval.failedExcluded': '失败样本不参与指标分母',
    'settings.eval.byLabelTitle': '按标注类型',
    'settings.eval.byLabelNote': '按语料标注（违规 / 合规）统计',
    'settings.eval.labelColumn': '标注类型',
    'settings.eval.correct': '正确',
    'settings.eval.accuracy': '准确率',
    'settings.eval.byLabelEmpty': '暂无分类统计',
    'settings.eval.byLabelEmptyHint': '后端返回 byLabel 后展示',
    'settings.eval.resultsTitle': '逐条结果',
    'settings.eval.resultsNote': '共 {count} 条，失败行标注 error',
    'settings.eval.corpusId': '语料 ID',
    'settings.eval.expected': '期望',
    'settings.eval.predicted': '预测',
    'settings.eval.confidence': '置信度',
    'settings.eval.reason': '理由',
    'settings.eval.resultsEmpty': '暂无逐条结果',
    'settings.eval.resultsEmptyHint': '后端返回 results 后展示期望 / 预测 / 置信度 / 理由',
    'settings.eval.empty':
      '还没有评测结果：选择供应商（可选模型）与样本数后点击「运行评测」。',
    'settings.eval.emptyHint': '评测会从语料库均衡抽取已标注样本，单次最多 20 条。',
    'settings.eval.hintA':
      'A/B 分流与评测接口均为 ADMIN 权限；评测调用真实模型并计入 AI 用量与配额，',
    'settings.eval.hintB': '单条失败计入失败数但不参与 accuracy / precision / recall 分母。',
    'settings.eval.callFailed': '调用失败',
    'settings.eval.errorForbidden': '当前账号无权限运行模型评测（需要 ADMIN）',
    'settings.eval.errorNotFound': '后端尚未提供模型评测接口（HTTP 404），请确认后端版本',
    'settings.eval.errorGeneric': '评测失败，请检查模型配置、AI 配额与后端日志',
    'settings.eval.selectProviderFirst': '请先选择评测供应商',

    // ⑤ RAG 检索增强
    'settings.rag.title': 'RAG 检索增强',
    'settings.rag.subtitle':
      '关闭时复筛行为与旧版完全一致；embedding 复用「AI 供应商」的接入地址与密钥',
    'settings.rag.unavailable': 'RAG 设置暂不可用',
    'settings.rag.enableLabel': '启用 RAG 检索增强',
    'settings.rag.source': '配置来源：{source}',
    'settings.rag.enableNoteA':
      '启用后复筛会召回知识库片段注入 prompt，并用语义 few-shot 补充语料示例；',
    'settings.rag.enableNoteB': '关闭时不发起任何 embedding 调用，行为与旧版一致。',
    'settings.rag.embeddingTitle': 'Embedding 配置（只读）',
    'settings.rag.testConnection': '连通性测试',
    'settings.rag.providerModel': '供应商 / 模型',
    'settings.rag.requestPath': '请求路径',
    'settings.rag.reuseHint': '复用 AI 供应商的 Base URL 与密钥',
    'settings.rag.dimensions': '向量维度',
    'settings.rag.timeout': '超时 {seconds} 秒',
    'settings.rag.keyNote': '密钥由后端加密存储，前端只读状态',
    'settings.rag.testOk': 'Embedding 连通正常',
    'settings.rag.testFail': 'Embedding 连通失败',
    'settings.rag.dimensionValue': '维度 {dimensions}',
    'settings.rag.testSuccess': 'Embedding 调用成功',
    'settings.rag.testFailHint': '请检查供应商 Base URL、密钥与模型名',
    'settings.rag.paramsTitle': '检索参数',
    'settings.rag.paramsNote': '保存后写入数据库并立即生效',
    'settings.rag.topK': 'Top K（1~10）',
    'settings.rag.minScore': '最低相似度（0~1）',
    'settings.rag.maxContextChars': '上下文上限（字符）',
    'settings.rag.corpusExamples': '语料 few-shot（0~10）',
    'settings.rag.save': '保存检索参数',
    'settings.rag.indexTitle': '索引统计',
    'settings.rag.lastReindex': '最近重建：{time}',
    'settings.rag.knowledgeReady': '知识库已就绪',
    'settings.rag.knowledgePendingFailed': '待处理 {pending} · 失败 {failed}',
    'settings.rag.corpusReady': '语料已向量化',
    'settings.rag.corpusReadyNote': '语义 few-shot 召回依赖语料 embedding',
    'settings.rag.vectorCapability': '向量能力',
    'settings.rag.vectorAlert':
      '向量能力不可用：RAG 会自动降级为不注入检索结果，不阻塞复筛；请检查 PostgreSQL pgvector 扩展。',
    'settings.rag.reindexKnowledge': '重建知识库索引',
    'settings.rag.reindexCorpus': '重建语料索引',
    'settings.rag.reindexAll': '全量重建',
    'settings.rag.reindexNote': '后台异步执行，可稍后「刷新」查看进度',
    'settings.rag.evalTitle': 'RAG 效果评测',
    'settings.rag.evalNote':
      '同一批语料对比「基线（无检索）」与「RAG（带检索）」的复筛指标',
    'settings.rag.evalSampleSize': '样本数（5~20）',
    'settings.rag.evalRun': '评测 RAG 效果',
    'settings.rag.evalCostHint': '评测调用真实模型，会计入 AI 用量与配额',
    'settings.rag.evalRunning': '正在逐条对比基线 / RAG 复筛（5~20 条，可能需要数十秒）…',
    'settings.rag.baselineDelta': '基线 {baseline} · Δ {delta}',
    'settings.rag.evalModel': '模型 {model}',
    'settings.rag.evalFailed': '基线失败 {baseline} · RAG 失败 {rag}',
    'settings.rag.evalCorrect': '正确数 基线 {baseline} / RAG {rag}',
    'settings.rag.evalCasesTitle': '逐条对比',
    'settings.rag.casesCount': '共 {count} 条',
    'settings.rag.colLabel': '标注',
    'settings.rag.colBaselinePrediction': '基线预测',
    'settings.rag.colRagPrediction': 'RAG 预测',
    'settings.rag.colConfidence': '置信度（基线 → RAG）',
    'settings.rag.colRefs': '引用',
    'settings.rag.colError': '错误',
    'settings.rag.casesEmpty': '暂无逐条结果',
    'settings.rag.casesEmptyHint': '后端返回 cases 后展示基线 / RAG 预测与引用',
    'settings.rag.evalEmpty': '还没有 RAG 评测结果：设置样本数（5~20）后点击「评测 RAG 效果」。',
    'settings.rag.hintA':
      'Embedding 供应商复用「AI 供应商」中的 Base URL 与密钥；未配置 / 不可用时 RAG 自动降级为',
    'settings.rag.hintB':
      '不注入检索结果，不阻塞复筛。索引重建与评测均为 ADMIN 接口，重建任务在后台异步执行。',
    'settings.rag.saved': 'RAG 检索参数已保存，立即生效',
    'settings.rag.enabledToast': 'RAG 检索增强已启用，立即生效',
    'settings.rag.disabledToast': 'RAG 检索增强已停用，复筛行为与旧版一致',
    'settings.rag.scopeKnowledge': '知识库',
    'settings.rag.scopeCorpus': '语料',
    'settings.rag.scopeAll': '全量',
    'settings.rag.reindexConfirmTitle': '全量重建索引',
    'settings.rag.reindexConfirmMessage':
      '将重新解析并索引全部知识库文档与语料条目，耗时可能较长，确认继续？',
    'settings.rag.reindexConfirmStart': '开始重建',
    'settings.rag.queued': '排队 {count} 条',
    'settings.rag.skipped': '跳过 {count} 条',
    'settings.rag.reindexSubmitted': '{scope}重建已提交{detail}（后台异步执行）',
    'settings.rag.reindexCountsDetail': '：{counts}',
    'settings.rag.countSeparator': '，',
    'settings.rag.refSeparator': '、',
    'settings.rag.loadFailed': 'RAG 设置加载失败，请稍后重试',
    'settings.rag.testFailed': 'Embedding 连通性测试失败，请检查供应商配置与网络',
    'settings.rag.evalFailedMsg': 'RAG 评测失败，请检查模型配置、AI 配额与后端日志',
    'settings.rag.vectorAvailableNote': 'pgvector 可用，语义检索正常参与复筛',
    'settings.rag.vectorUnavailableNote': 'RAG 将自动降级为不注入检索结果，不阻塞复筛',
    'settings.rag.vectorUnknownNote': '接口未返回向量能力状态（后端可能为旧版本）',

    // ⑥ 生效范围与密钥
    'settings.scope.title': '生效范围与密钥配置',
    'settings.scope.aiScreening': 'AI 语义复筛',
    'settings.scope.aiScreeningNote': 'DFA 命中后的语境判断，结论决定是否转人工',
    'settings.scope.mining': '敏感词挖掘',
    'settings.scope.miningNote': '确认违规后由 AI 提炼新词入库（默认停用待审核）',
    'settings.scope.transcription': '语音转写（Whisper）',
    'settings.scope.transcriptionNote': '固定走本机 whisper.cpp；chat 类供应商不提供转写能力',
    'settings.scope.dfa': 'DFA 初筛',
    'settings.scope.dfaNote': '本地 Aho-Corasick 匹配，不调用外部模型',
    'settings.scope.envTitle': '在 .env 中补充密钥（docker compose 自动读取）',
    'settings.scope.envKeyComment': 'DeepSeek 密钥（切换前必须先配置）',
    'settings.scope.envOptionalComment': '可选：让 DeepSeek 成为首次启动的默认模型',

    // ⑦ 抽样与通知
    'settings.notify.title': '抽样与通知',
    'settings.notify.subtitle': '合规抽样与 Webhook 告警，仅管理员可查看',
    'settings.notify.unavailable': '通知设置暂不可用',
    'settings.notify.sampleRate': 'DFA 未命中抽样比例',
    'settings.notify.webhook': 'Webhook 通知',
    'settings.notify.backlog': '待复核积压阈值',
    'settings.notify.backlogNote': '待人工复检数超过阈值时每日告警（Asia/Shanghai）',
    'settings.notify.events': '通知事件',
    'settings.notify.eventsEmpty': '接口未返回事件列表（后端可能为旧版本）',
    'settings.notify.sendTest': '发送测试 Webhook',
    'settings.notify.testOk': '测试通知发送成功',
    'settings.notify.testFail': '测试通知发送失败',
    'settings.notify.testSuccessMsg': 'Webhook 已接收测试事件',
    'settings.notify.testFailMsg': '请检查 Webhook 地址、签名密钥与网络连通性',
    'settings.notify.hintA':
      '抽样比例、Webhook 地址 / 密钥 / 超时、积压阈值均由后端环境变量配置',
    'settings.notify.hintB': '（APP_SAMPLE_DFA_MISS_RATE、APP_NOTIFY_*），修改后需重启后端。',
    'settings.notify.hintC': '通知只发送到后端配置的地址，前端不接触 URL 与签名密钥。',
    'settings.notify.sampleRateNote': 'DFA 无命中的录音按该比例进入 AI 语义复筛 / 人工复检',
    'settings.notify.sampleRateMissing':
      '后端未返回当前值：由环境变量 APP_SAMPLE_DFA_MISS_RATE 配置（0~1，重启生效）',
    'settings.notify.disabledNote': 'APP_NOTIFY_ENABLED 未开启，不会发送任何通知',
    'settings.notify.urlMissingNote': '已启用但 Webhook URL 未配置，事件不会送达',
    'settings.notify.enabledNote': '地址与签名密钥由后端环境变量配置，前端只读',
    'settings.notify.urlUnknownNote': '接口未返回 URL 配置状态',
    'settings.notify.enabledUnknownNote': '接口未返回启用状态',
    'settings.notify.unknownNote': '接口未返回通知状态，可能是后端版本较旧',
    'settings.notify.backlogValue': '{count} 条',
    'settings.notify.hintDisabled': '通知未启用：需在后端开启 APP_NOTIFY_ENABLED',
    'settings.notify.hintUrlMissing': 'Webhook URL 未配置：需在后端配置 APP_NOTIFY_WEBHOOK_URL',
    'settings.notify.hintTest':
      '发送一条 NOTIFY_TEST 事件，验证地址连通性与签名，结果会写入操作审计',
    'settings.notify.loadFailed': '通知设置加载失败，请稍后重试',
    'settings.notify.testFailedMsg': '测试通知发送失败，请检查 Webhook 配置与网络',

    // 管理员接口错误
    'settings.error.forbidden': '当前账号无权限执行该操作（需要 ADMIN）',
    'settings.error.notFound': '后端尚未提供该接口（HTTP 404），请确认后端版本'
  },
  en: {
    // Shared state chips
    'settings.state.enabled': 'Enabled',
    'settings.state.disabled': 'Not enabled',
    'settings.state.configured': 'Configured',
    'settings.state.notConfigured': 'Not configured',
    'settings.state.available': 'Available',
    'settings.state.unavailable': 'Unavailable',
    'settings.state.urlMissing': 'URL not configured',

    // Config source
    'settings.source.configDefault': 'Config default',
    'settings.source.uiSwitch': 'Switched in UI',
    'settings.source.uiSaved': 'Saved in UI',

    // ① Active model
    'settings.active.title': 'Active AI screening model',
    'settings.active.subtitle': 'Used for semantic screening and keyword mining',
    'settings.active.keyConfigured': 'API key configured and ready to call',
    'settings.active.keyMissing':
      'API key not configured: recordings with DFA hits fall back to manual review, so nothing is missed',
    'settings.active.testConnection': 'Test connection',
    'settings.active.switched': 'Switched to {provider} · {model}',

    // Connection test result (shared by model / provider)
    'settings.test.ok': 'Connection OK',
    'settings.test.fail': 'Connection failed',
    'settings.test.latency': 'Took {ms} ms',
    'settings.test.emptyReply': '(empty response)',

    // ② Switch model
    'settings.model.title': 'Switch model',
    'settings.model.subtitle':
      'Takes effect immediately after saving — no restart needed; the selection is persisted',
    'settings.model.providerDisabled':
      'This provider is disabled under AI Providers. Enable it before switching',
    'settings.model.keyConfigured': 'Key configured',
    'settings.model.keyMissingEnv': 'Missing {env}',
    'settings.model.keyMissing': 'Key not configured',
    'settings.model.label': 'Model',
    'settings.model.placeholder': 'Select or type a model name',
    'settings.model.test': 'Test',
    'settings.model.save': 'Save and apply',
    'settings.model.hintSelect':
      'Pick from the list or type a model that is not listed (no config change or restart needed when a provider ships a new model).',
    'settings.model.hintEffect':
      'Switching affects all later screening and keyword mining runs; recordings that already have results are not re-evaluated.',
    'settings.model.readonlyAlert':
      'Read-only roles can view the active model; switching models and connection tests require an administrator.',

    // ③ AI provider registry
    'settings.provider.title': 'AI Providers',
    'settings.provider.subtitle':
      'Built-in providers are seeded from backend config on first startup; keys are encrypted and only shown masked',
    'settings.provider.create': 'New provider',
    'settings.provider.editTitle': 'Edit provider',
    'settings.provider.current': 'Active',
    'settings.provider.builtIn': 'Built-in',
    'settings.provider.unavailable': 'Provider list is unavailable',
    'settings.provider.columnName': 'Provider',
    'settings.provider.baseUrl': 'Endpoint',
    'settings.provider.defaultChatPath': 'Default chat path',
    'settings.provider.defaultModel': 'Default model',
    'settings.provider.modelCount': 'Candidates: {count}',
    'settings.provider.pricePer1k': 'Price / 1K',
    'settings.provider.priceTooltip': 'USD per 1K tokens; in = input price, out = output price',
    'settings.provider.priceIn': 'In',
    'settings.provider.priceOut': 'Out',
    'settings.provider.keyColumn': 'API key',
    'settings.provider.test': 'Test',
    'settings.provider.activeDeleteTooltip':
      'The active provider cannot be deleted. Switch to another model first',
    'settings.provider.deleteConfirm':
      'The provider configuration and API key will be removed permanently. Delete anyway?',
    'settings.provider.empty': 'No providers yet',
    'settings.provider.emptyHint':
      'Built-in providers are seeded when the backend first starts; use “New provider” in the top right to connect a custom gateway',
    'settings.provider.hintA':
      'Built-in providers can have their endpoint, pricing and key edited but cannot be deleted; custom providers can be added and removed.',
    'settings.provider.hintB':
      'Disabling or deleting a provider does not affect past recording results or existing call logs.',
    'settings.provider.loadFailed': 'Failed to load providers. Please try again later',
    'settings.provider.testFailed': 'Test request failed. Check the gateway and backend logs',
    'settings.provider.updated': 'Provider {label} updated',
    'settings.provider.created': 'Provider {label} created',
    'settings.provider.deleted': 'Provider {label} deleted',
    'settings.provider.saveEdit': 'Save changes',
    'settings.provider.createSubmit': 'Create provider',
    'settings.provider.formKey': 'Key',
    'settings.provider.formKeyPlaceholder': 'e.g. my-gateway (lowercase letters / digits / . _ -)',
    'settings.provider.formLabelPlaceholder': 'Display name, e.g. Internal gateway',
    'settings.provider.formDefaultModelPlaceholder': 'e.g. gpt-4o-mini',
    'settings.provider.formModels': 'Models',
    'settings.provider.formModelsPlaceholder': 'Type a model name and press Enter',
    'settings.provider.formModelsHint':
      'Used by the model switcher and connection tests; the default model is always included.',
    'settings.provider.formInputPrice': 'Input',
    'settings.provider.formOutputPrice': 'Output',
    'settings.provider.formPriceHint':
      'USD per 1K tokens, snapshotted into the cost of each call; 0 excludes the provider from cost reporting.',
    'settings.provider.formMaxTokens': 'Max output tokens',
    'settings.provider.formMaxTokensPlaceholder': 'Leave empty to use the provider default',
    'settings.provider.formJsonMode': 'JSON mode',
    'settings.provider.formJsonModeHint': 'Require the model to return a JSON object',
    'settings.provider.formEnabled': 'Enabled',
    'settings.provider.formEnabledHint': 'A disabled provider cannot be set as the active model',
    'settings.provider.formApiKeyKeep': 'Leave empty to keep unchanged (current: {masked})',
    'settings.provider.formApiKeyOptional': 'Optional — you can add it later',
    'settings.provider.formApiKeyHint':
      'Sent to the backend for encrypted storage only; the API and UI always see masked values.',
    'settings.provider.formBuiltInAlert':
      'Built-in providers can have their endpoint, pricing and key edited but cannot be deleted; leave the key empty to keep it unchanged.',
    'settings.provider.ruleLabelRequired': 'Please enter a name',
    'settings.provider.ruleBaseUrlRequired': 'Please enter a Base URL',
    'settings.provider.ruleBaseUrlPattern': 'Must start with http(s)://',
    'settings.provider.ruleDefaultModelRequired': 'Please enter a default model',
    'settings.provider.ruleKeyRequired': 'Please enter a key',
    'settings.provider.ruleKeyPattern':
      '1-64 characters: lowercase letters, digits, . _ - (must start with a letter or digit)',

    // Provider connection test dialog
    'settings.providerTest.title': 'Test provider connection',
    'settings.providerTest.disabledChip': 'Disabled',
    'settings.providerTest.model': 'Test model',
    'settings.providerTest.modelPlaceholder': 'Leave empty to use the default model: {model}',
    'settings.providerTest.failHint': 'Check the Base URL / API key / model name',
    'settings.providerTest.send': 'Send test request',

    // ④ A/B split
    'settings.ab.title': 'A/B and model evaluation',
    'settings.ab.subtitle':
      'A/B config is read-only; evaluation is triggered manually with up to 20 corpus samples per run',
    'settings.ab.split': 'A/B split',
    'settings.ab.percent': 'Group B traffic share',
    'settings.ab.percentNote':
      'Traffic enters group B by probability; falls back to the active model when disabled or the standby provider is unavailable',
    'settings.ab.group': 'Group B provider / model',
    'settings.ab.providerDefault': 'Provider default',
    'settings.ab.groupNote':
      'Only semantic screening is split; keyword mining always uses the active model',
    'settings.ab.missing':
      'The API returned no A/B config (older backend, or A/B is not configured)',
    'settings.ab.pending':
      'A/B config loads together with the active AI screening model and is unavailable right now',
    'settings.ab.stateNotConfigured': 'Not configured',
    'settings.ab.stateUnknown': 'State unknown',
    'settings.ab.noteNoConfig': 'The API returned no A/B config; the backend may be outdated',
    'settings.ab.noteDisabled': 'A/B split is off; all traffic uses the active model',
    'settings.ab.notePercentMissing': 'Enabled, but the API returned no traffic share',
    'settings.ab.notePercent': 'About {percent} of semantic screening traffic goes to group B',
    'settings.ab.noteUnknown': 'The API returned no enabled state',

    // ④ Model evaluation
    'settings.eval.provider': 'Provider',
    'settings.eval.providerPlaceholder': 'Select a provider',
    'settings.eval.modelLabel': 'Model (optional)',
    'settings.eval.modelPlaceholder': 'Leave empty to use the provider default model',
    'settings.eval.sampleSize': 'Samples',
    'settings.eval.run': 'Run evaluation',
    'settings.eval.providerUnavailable':
      'Provider list unavailable: check the configuration under AI Providers above',
    'settings.eval.running': 'Evaluating samples one by one (1-20 items, may take tens of seconds)…',
    'settings.eval.incomplete': 'Evaluation incomplete',
    'settings.eval.evaluatedRatio': 'Evaluated / total',
    'settings.eval.correctFailed': 'Correct {correct} · Failed {failed}',
    'settings.eval.total': 'Total {total}',
    'settings.eval.latency': 'Duration {value}',
    'settings.eval.failedExcluded': 'Failed samples are excluded from metric denominators',
    'settings.eval.byLabelTitle': 'By label',
    'settings.eval.byLabelNote': 'Grouped by corpus label (violation / compliant)',
    'settings.eval.labelColumn': 'Label',
    'settings.eval.correct': 'Correct',
    'settings.eval.accuracy': 'Accuracy',
    'settings.eval.byLabelEmpty': 'No label breakdown yet',
    'settings.eval.byLabelEmptyHint': 'Shown once the backend returns byLabel',
    'settings.eval.resultsTitle': 'Per-sample results',
    'settings.eval.resultsNote': 'Items: {count}; failed rows are marked as error',
    'settings.eval.corpusId': 'Corpus ID',
    'settings.eval.expected': 'Expected',
    'settings.eval.predicted': 'Predicted',
    'settings.eval.confidence': 'Confidence',
    'settings.eval.reason': 'Reason',
    'settings.eval.resultsEmpty': 'No per-sample results yet',
    'settings.eval.resultsEmptyHint':
      'Shown once the backend returns results (expected / predicted / confidence / reason)',
    'settings.eval.empty':
      'No evaluation yet: select a provider (and optionally a model) and a sample size, then click “Run evaluation”.',
    'settings.eval.emptyHint':
      'Evaluation draws labeled samples evenly from the corpus, up to 20 per run.',
    'settings.eval.hintA':
      'A/B split and evaluation endpoints require ADMIN. Evaluation calls the real model and counts toward AI usage and quota; ',
    'settings.eval.hintB':
      'failed samples count as failures but are excluded from the accuracy / precision / recall denominators.',
    'settings.eval.callFailed': 'Call failed',
    'settings.eval.errorForbidden': 'Your account cannot run model evaluation (ADMIN required)',
    'settings.eval.errorNotFound':
      'The backend does not expose the model evaluation endpoint (HTTP 404); check the backend version',
    'settings.eval.errorGeneric':
      'Evaluation failed. Check the model configuration, AI quota and backend logs',
    'settings.eval.selectProviderFirst': 'Select a provider before running the evaluation',

    // ⑤ RAG retrieval
    'settings.rag.title': 'RAG retrieval',
    'settings.rag.subtitle':
      'When off, screening behaves exactly as before; embeddings reuse the AI provider endpoint and key',
    'settings.rag.unavailable': 'RAG settings are unavailable',
    'settings.rag.enableLabel': 'Enable RAG retrieval',
    'settings.rag.source': 'Source: {source}',
    'settings.rag.enableNoteA':
      'When enabled, screening recalls knowledge base chunks into the prompt and adds semantic few-shot corpus samples;',
    'settings.rag.enableNoteB':
      'when disabled, no embedding calls are made and behavior matches the previous version.',
    'settings.rag.embeddingTitle': 'Embedding configuration (read-only)',
    'settings.rag.testConnection': 'Test connection',
    'settings.rag.providerModel': 'Provider / model',
    'settings.rag.requestPath': 'Request path',
    'settings.rag.reuseHint': 'Reuses the AI provider Base URL and API key',
    'settings.rag.dimensions': 'Dimensions',
    'settings.rag.timeout': 'Timeout {seconds}s',
    'settings.rag.keyNote': 'The key is stored encrypted by the backend; the UI only shows status',
    'settings.rag.testOk': 'Embedding connection OK',
    'settings.rag.testFail': 'Embedding connection failed',
    'settings.rag.dimensionValue': 'Dimensions {dimensions}',
    'settings.rag.testSuccess': 'Embedding call succeeded',
    'settings.rag.testFailHint': 'Check the provider Base URL, API key and model name',
    'settings.rag.paramsTitle': 'Retrieval parameters',
    'settings.rag.paramsNote': 'Saved to the database and applied immediately',
    'settings.rag.topK': 'Top K (1-10)',
    'settings.rag.minScore': 'Minimum similarity (0-1)',
    'settings.rag.maxContextChars': 'Max context (characters)',
    'settings.rag.corpusExamples': 'Corpus few-shot (0-10)',
    'settings.rag.save': 'Save retrieval parameters',
    'settings.rag.indexTitle': 'Index statistics',
    'settings.rag.lastReindex': 'Last rebuilt: {time}',
    'settings.rag.knowledgeReady': 'Knowledge base ready',
    'settings.rag.knowledgePendingFailed': 'Pending {pending} · Failed {failed}',
    'settings.rag.corpusReady': 'Corpus embedded',
    'settings.rag.corpusReadyNote': 'Semantic few-shot recall depends on corpus embeddings',
    'settings.rag.vectorCapability': 'Vector capability',
    'settings.rag.vectorAlert':
      'Vector capability unavailable: RAG degrades to not injecting retrieval results and does not block screening; check the PostgreSQL pgvector extension.',
    'settings.rag.reindexKnowledge': 'Rebuild knowledge index',
    'settings.rag.reindexCorpus': 'Rebuild corpus index',
    'settings.rag.reindexAll': 'Rebuild all',
    'settings.rag.reindexNote': 'Runs asynchronously; click “Refresh” later to see progress',
    'settings.rag.evalTitle': 'RAG effectiveness evaluation',
    'settings.rag.evalNote':
      'Compares screening metrics of the baseline (no retrieval) and RAG (with retrieval) on the same corpus batch',
    'settings.rag.evalSampleSize': 'Samples (5-20)',
    'settings.rag.evalRun': 'Evaluate RAG',
    'settings.rag.evalCostHint': 'Calls the real model and counts toward AI usage and quota',
    'settings.rag.evalRunning':
      'Comparing baseline / RAG screening sample by sample (5-20 items, may take tens of seconds)…',
    'settings.rag.baselineDelta': 'Baseline {baseline} · Δ {delta}',
    'settings.rag.evalModel': 'Model {model}',
    'settings.rag.evalFailed': 'Baseline failures {baseline} · RAG failures {rag}',
    'settings.rag.evalCorrect': 'Correct answers — baseline {baseline} / RAG {rag}',
    'settings.rag.evalCasesTitle': 'Sample comparison',
    'settings.rag.casesCount': 'Items: {count}',
    'settings.rag.colLabel': 'Label',
    'settings.rag.colBaselinePrediction': 'Baseline prediction',
    'settings.rag.colRagPrediction': 'RAG prediction',
    'settings.rag.colConfidence': 'Confidence (baseline → RAG)',
    'settings.rag.colRefs': 'References',
    'settings.rag.colError': 'Error',
    'settings.rag.casesEmpty': 'No sample results yet',
    'settings.rag.casesEmptyHint':
      'Shown once the backend returns cases (baseline / RAG predictions and references)',
    'settings.rag.evalEmpty':
      'No RAG evaluation yet: set the sample size (5-20) and click “Evaluate RAG”.',
    'settings.rag.hintA':
      'The embedding provider reuses the AI provider Base URL and key; when unconfigured / unavailable, RAG degrades to',
    'settings.rag.hintB':
      'not injecting retrieval results without blocking screening. Reindex and evaluation endpoints require ADMIN; reindex runs asynchronously in the background.',
    'settings.rag.saved': 'RAG retrieval parameters saved and applied',
    'settings.rag.enabledToast': 'RAG retrieval enabled and applied',
    'settings.rag.disabledToast': 'RAG retrieval disabled; screening behaves as before',
    'settings.rag.scopeKnowledge': 'Knowledge base',
    'settings.rag.scopeCorpus': 'Corpus',
    'settings.rag.scopeAll': 'All',
    'settings.rag.reindexConfirmTitle': 'Rebuild all indexes',
    'settings.rag.reindexConfirmMessage':
      'All knowledge documents and corpus entries will be re-parsed and re-indexed; this may take a while. Continue?',
    'settings.rag.reindexConfirmStart': 'Start rebuild',
    'settings.rag.queued': '{count} queued',
    'settings.rag.skipped': '{count} skipped',
    'settings.rag.reindexSubmitted': '{scope} rebuild submitted{detail} (runs in the background)',
    'settings.rag.reindexCountsDetail': ': {counts}',
    'settings.rag.countSeparator': ', ',
    'settings.rag.refSeparator': ', ',
    'settings.rag.loadFailed': 'Failed to load RAG settings. Please try again later',
    'settings.rag.testFailed':
      'Embedding connection test failed. Check the provider configuration and network',
    'settings.rag.evalFailedMsg':
      'RAG evaluation failed. Check the model configuration, AI quota and backend logs',
    'settings.rag.vectorAvailableNote':
      'pgvector is available; semantic retrieval participates in screening',
    'settings.rag.vectorUnavailableNote':
      'RAG degrades to not injecting retrieval results without blocking screening',
    'settings.rag.vectorUnknownNote':
      'The API returned no vector capability status (the backend may be outdated)',

    // ⑥ Scope and keys
    'settings.scope.title': 'Scope and key configuration',
    'settings.scope.aiScreening': 'AI semantic screening',
    'settings.scope.aiScreeningNote':
      'Context judgement after a DFA hit; the result decides whether human review is needed',
    'settings.scope.mining': 'Sensitive word mining',
    'settings.scope.miningNote':
      'After a confirmed violation, AI extracts new entries into the dictionary (disabled pending review by default)',
    'settings.scope.transcription': 'Speech transcription (Whisper)',
    'settings.scope.transcriptionNote':
      'Always uses the local whisper.cpp; chat-only providers do not offer transcription',
    'settings.scope.dfa': 'DFA screening',
    'settings.scope.dfaNote': 'Local Aho-Corasick matching; no external model calls',
    'settings.scope.envTitle': 'Add the key to .env (docker compose picks it up automatically)',
    'settings.scope.envKeyComment': 'DeepSeek key (must be configured before switching)',
    'settings.scope.envOptionalComment':
      'Optional: make DeepSeek the default model on first startup',

    // ⑦ Sampling and notifications
    'settings.notify.title': 'Sampling and notifications',
    'settings.notify.subtitle':
      'Compliance sampling and webhook alerts; visible to administrators only',
    'settings.notify.unavailable': 'Notification settings are unavailable',
    'settings.notify.sampleRate': 'DFA-miss sampling rate',
    'settings.notify.webhook': 'Webhook notifications',
    'settings.notify.backlog': 'Review backlog threshold',
    'settings.notify.backlogNote':
      'Alerts daily when pending reviews exceed the threshold (Asia/Shanghai)',
    'settings.notify.events': 'Notification events',
    'settings.notify.eventsEmpty':
      'The API returned no event list (the backend may be outdated)',
    'settings.notify.sendTest': 'Send test webhook',
    'settings.notify.testOk': 'Test notification sent',
    'settings.notify.testFail': 'Test notification failed',
    'settings.notify.testSuccessMsg': 'The webhook received the test event',
    'settings.notify.testFailMsg': 'Check the webhook URL, signing secret and network connectivity',
    'settings.notify.hintA':
      'Sampling rate, webhook URL / secret / timeout and the backlog threshold are configured through backend environment variables',
    'settings.notify.hintB':
      ' (APP_SAMPLE_DFA_MISS_RATE, APP_NOTIFY_*); restart the backend after changing them.',
    'settings.notify.hintC':
      'Notifications go only to the configured backend address; the frontend never handles the URL or signing secret.',
    'settings.notify.sampleRateNote':
      'Recordings without DFA hits enter AI semantic screening / manual review at this rate',
    'settings.notify.sampleRateMissing':
      'The backend returned no value: configured by APP_SAMPLE_DFA_MISS_RATE (0-1, requires a restart)',
    'settings.notify.disabledNote': 'APP_NOTIFY_ENABLED is off; no notifications are sent',
    'settings.notify.urlMissingNote':
      'Enabled, but no webhook URL is configured so events are not delivered',
    'settings.notify.enabledNote':
      'URL and signing secret are configured through backend environment variables; read-only in the UI',
    'settings.notify.urlUnknownNote': 'The API returned no URL configuration status',
    'settings.notify.enabledUnknownNote': 'The API returned no enabled state',
    'settings.notify.unknownNote':
      'The API returned no notification status; the backend may be outdated',
    'settings.notify.backlogValue': 'Records: {count}',
    'settings.notify.hintDisabled': 'Notifications are off: enable APP_NOTIFY_ENABLED on the backend',
    'settings.notify.hintUrlMissing':
      'No webhook URL: configure APP_NOTIFY_WEBHOOK_URL on the backend',
    'settings.notify.hintTest':
      'Send a NOTIFY_TEST event to verify connectivity and signing; the result is written to the audit log',
    'settings.notify.loadFailed': 'Failed to load notification settings. Please try again later',
    'settings.notify.testFailedMsg': 'Test notification failed. Check the webhook configuration and network',

    // Admin API errors
    'settings.error.forbidden': 'Your account cannot perform this action (ADMIN required)',
    'settings.error.notFound':
      'The backend does not expose this endpoint (HTTP 404); check the backend version'
  }
}
