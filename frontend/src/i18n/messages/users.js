/**
 * users 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `users.` 前缀。
 */
export default {
  zh: {
    'users.title': '用户管理',
    'users.accountCount.one': '{count} 个账号',
    'users.accountCount.other': '{count} 个账号',
    'users.adminCount.one': '管理员 {count}',
    'users.adminCount.other': '管理员 {count}',
    'users.disabledCount': '{count} 个已禁用',
    'users.searchPlaceholder': '搜索用户名 / 显示名',
    'users.create': '新建用户',
    'users.roleHint':
      '角色：管理员 拥有全部权限；复核员 可上传、认领与提交复核结论；只读 仅可查看。',
    'users.securityHint':
      '禁用账号不会删除历史数据与审计记录，可随时重新启用；密码以 BCrypt 存储，最少 8 位。',

    // 列表列
    'users.username': '用户名',
    'users.displayName': '显示名',
    'users.role': '角色',
    'users.statusDisabled': '已禁用',
    'users.self': '当前账号',
    'users.emptyFilteredTitle': '没有匹配的账号',
    'users.emptyTitle': '暂无用户',
    'users.emptyFilteredHint': '换个关键词试试',
    'users.emptyHint': '点击右上角「新建用户」创建第一个账号',

    // 新建 / 编辑
    'users.createTitle': '新建用户',
    'users.editTitle': '编辑用户',
    'users.usernamePlaceholder': '登录账号，小写字母/数字/._-',
    'users.displayNamePlaceholder': '如：张合规',
    'users.resetPassword': '重置密码',
    'users.initialPassword': '初始密码',
    'users.passwordKeepPlaceholder': '留空则不修改（至少 8 位）',
    'users.passwordPlaceholder': '至少 8 位',
    'users.disableHint': '禁用后该账号无法登录，历史审计保留',
    'users.selfAlert': '这是当前登录账号，修改角色/禁用可能影响本会话的权限',
    'users.saveChanges': '保存修改',
    'users.createSubmit': '创建用户',

    // 操作
    'users.disableConfirm': '禁用后该账号将无法登录，确认禁用？',
    'users.disable': '禁用',
    'users.enable': '启用',

    // 表单校验
    'users.usernameRequired': '请输入用户名',
    'users.usernamePattern': '3-32 位小写字母/数字/._-',
    'users.displayNameRequired': '请输入显示名',
    'users.roleRequired': '请选择角色',
    'users.passwordRequired': '请输入初始密码',
    'users.passwordMin': '密码至少 8 位',

    // 反馈
    'users.loadFailed': '用户列表加载失败',
    'users.updated': '用户已更新',
    'users.created': '用户已创建',
    'users.disabledSuccess': '已禁用 {name}',
    'users.enabledSuccess': '已启用 {name}'
  },
  en: {
    'users.title': 'User Management',
    'users.accountCount.one': '{count} account',
    'users.accountCount.other': '{count} accounts',
    'users.adminCount.one': 'Admin {count}',
    'users.adminCount.other': 'Admins {count}',
    'users.disabledCount': '{count} disabled',
    'users.searchPlaceholder': 'Search username / display name',
    'users.create': 'New user',
    'users.roleHint':
      'Roles: administrators have full access; reviewers can upload, claim recordings and submit review results; read-only users can only view.',
    'users.securityHint':
      'Disabling an account neither deletes its history nor its audit records, and it can be re-enabled at any time. Passwords are stored with BCrypt and must be at least 8 characters.',

    // Table columns
    'users.username': 'Username',
    'users.displayName': 'Display name',
    'users.role': 'Role',
    'users.statusDisabled': 'Disabled',
    'users.self': 'Current account',
    'users.emptyFilteredTitle': 'No matching accounts',
    'users.emptyTitle': 'No users yet',
    'users.emptyFilteredHint': 'Try another keyword',
    'users.emptyHint': 'Click "New user" in the top right to create the first account',

    // Create / edit
    'users.createTitle': 'New User',
    'users.editTitle': 'Edit User',
    'users.usernamePlaceholder': 'Sign-in name: lowercase letters, digits, . _ -',
    'users.displayNamePlaceholder': 'e.g. Alex Chen',
    'users.resetPassword': 'Reset password',
    'users.initialPassword': 'Initial password',
    'users.passwordKeepPlaceholder': 'Leave blank to keep unchanged (at least 8 characters)',
    'users.passwordPlaceholder': 'At least 8 characters',
    'users.disableHint': 'Disabled accounts cannot sign in; audit history is kept',
    'users.selfAlert':
      'This is the signed-in account. Changing its role or disabling it may affect the permissions of this session.',
    'users.saveChanges': 'Save changes',
    'users.createSubmit': 'Create user',

    // Actions
    'users.disableConfirm':
      'The account will no longer be able to sign in after being disabled. Disable it?',
    'users.disable': 'Disable',
    'users.enable': 'Enable',

    // Validation
    'users.usernameRequired': 'Please enter a username',
    'users.usernamePattern': '3-32 lowercase letters, digits, . _ -',
    'users.displayNameRequired': 'Please enter a display name',
    'users.roleRequired': 'Please select a role',
    'users.passwordRequired': 'Please enter an initial password',
    'users.passwordMin': 'Password must be at least 8 characters',

    // Feedback
    'users.loadFailed': 'Failed to load users',
    'users.updated': 'User updated',
    'users.created': 'User created',
    'users.disabledSuccess': 'Disabled {name}',
    'users.enabledSuccess': 'Enabled {name}'
  }
}
