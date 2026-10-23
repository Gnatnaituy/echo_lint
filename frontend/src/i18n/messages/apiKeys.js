/**
 * apiKeys 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `apiKeys.` 前缀。
 */
export default {
  zh: {
    'apiKeys.title': '开放 API 密钥',
    'apiKeys.count.one': '{count} 个密钥',
    'apiKeys.count.other': '{count} 个密钥',
    'apiKeys.enabledCount': '{count} 个启用中',
    'apiKeys.create': '创建密钥',
    'apiKeys.hint': '外部系统通过请求头 X-API-Key 调用 /api/open/**（如上传录音、查询处理状态）。',
    'apiKeys.securityHint':
      '密钥只在创建时显示一次明文，服务端仅保存 SHA-256 哈希；停用后立即拒绝请求但保留审计记录。',

    // 列表列
    'apiKeys.key': '密钥',
    'apiKeys.untitled': '未命名密钥',
    'apiKeys.statusRevoked': '已停用',
    'apiKeys.revokeConfirm': '停用后使用该密钥的请求会立即返回 401，确认停用？',
    'apiKeys.revoke': '停用',
    'apiKeys.lastUsed': '最近使用',
    'apiKeys.neverUsed': '从未使用',
    'apiKeys.emptyTitle': '还没有 API 密钥',
    'apiKeys.emptyHint': '创建密钥后，外部系统即可用 X-API-Key 头调用开放接口',

    // 创建
    'apiKeys.createTitle': '创建 API 密钥',
    'apiKeys.namePlaceholder': '用途 / 调用方，如 工单系统',
    'apiKeys.createAlert': '创建后明文密钥只显示一次，请当场复制保存；之后列表只能看到前缀掩码。',
    'apiKeys.createdTitle': '密钥已创建',
    'apiKeys.createdHint': '仅显示一次：关闭后无法再次查看完整密钥',
    'apiKeys.plaintextMissing': '（后端未返回明文密钥）',
    'apiKeys.copyKey': '复制密钥',
    'apiKeys.saveAlert':
      '请立即妥善保存：密钥泄露后请停用并重新创建；不要提交到代码仓库或写入日志。',
    'apiKeys.savedConfirm': '我已保存',

    // 错误 / 校验
    'apiKeys.forbidden': '当前账号无权限访问 API 密钥（需要 ADMIN）',
    'apiKeys.notAvailable': '后端尚未提供 API 密钥接口（HTTP 404），请确认后端版本',
    'apiKeys.loadFailed': 'API 密钥加载失败，请稍后重试',
    'apiKeys.nameRequired': '请输入密钥名称',
    'apiKeys.nameLength': '名称 1-64 个字符',

    // 反馈
    'apiKeys.copied': '密钥已复制到剪贴板',
    'apiKeys.copyFailed': '复制失败，请手动选择密钥复制',
    'apiKeys.revokedSuccess': '密钥「{name}」已停用'
  },
  en: {
    'apiKeys.title': 'Open API Keys',
    'apiKeys.count.one': '{count} key',
    'apiKeys.count.other': '{count} keys',
    'apiKeys.enabledCount': '{count} enabled',
    'apiKeys.create': 'Create key',
    'apiKeys.hint':
      'External systems call /api/open/** with the X-API-Key request header (e.g. to upload recordings or query processing status).',
    'apiKeys.securityHint':
      'The plaintext key is shown only once at creation and the server stores only its SHA-256 hash. Revoked keys are rejected immediately, but their audit records are kept.',

    // Table columns
    'apiKeys.key': 'Key',
    'apiKeys.untitled': 'Untitled key',
    'apiKeys.statusRevoked': 'Revoked',
    'apiKeys.revokeConfirm':
      'Requests using this key will immediately receive 401 after revocation. Revoke it?',
    'apiKeys.revoke': 'Revoke',
    'apiKeys.lastUsed': 'Last used',
    'apiKeys.neverUsed': 'Never used',
    'apiKeys.emptyTitle': 'No API keys yet',
    'apiKeys.emptyHint':
      'Once a key is created, external systems can call the open API with the X-API-Key header',

    // Create
    'apiKeys.createTitle': 'Create API Key',
    'apiKeys.namePlaceholder': 'Purpose / caller, e.g. Ticketing system',
    'apiKeys.createAlert':
      'The plaintext key is shown only once after creation. Copy and store it now; afterwards the list only shows the masked prefix.',
    'apiKeys.createdTitle': 'API Key Created',
    'apiKeys.createdHint': 'Shown only once: the full key cannot be viewed again after closing',
    'apiKeys.plaintextMissing': '(the backend did not return a plaintext key)',
    'apiKeys.copyKey': 'Copy key',
    'apiKeys.saveAlert':
      'Store it securely now. If the key leaks, revoke it and create a new one; never commit it to a repository or write it to logs.',
    'apiKeys.savedConfirm': 'I have saved it',

    // Errors / validation
    'apiKeys.forbidden': 'This account cannot access API keys (ADMIN required)',
    'apiKeys.notAvailable':
      'The backend does not provide the API key endpoint yet (HTTP 404). Check the backend version.',
    'apiKeys.loadFailed': 'Failed to load API keys. Please try again later.',
    'apiKeys.nameRequired': 'Please enter a key name',
    'apiKeys.nameLength': 'Name must be 1-64 characters',

    // Feedback
    'apiKeys.copied': 'Key copied to clipboard',
    'apiKeys.copyFailed': 'Copy failed. Please select and copy the key manually.',
    'apiKeys.revokedSuccess': 'Key "{name}" revoked'
  }
}
