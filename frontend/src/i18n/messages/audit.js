/**
 * audit 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `audit.` 前缀。
 */
export default {
  zh: {
    'audit.title': '操作审计',
    'audit.subtitle': '审计记录当前登录人与请求 IP；写入失败不影响主流程',
    'audit.actorPlaceholder': '操作人',
    'audit.allActions': '全部动作',
    'audit.search': '查询',
    'audit.listHint': '按时间倒序分页；点击任意行查看完整详情。',

    // 列表列
    'audit.time': '时间',
    'audit.actor': '操作人',
    'audit.action': '动作',
    'audit.target': '对象',
    'audit.emptyTitle': '暂无审计记录',
    'audit.emptyHint': '登录、用户管理、录音处理、复核与配置变更会自动写入审计',

    // 详情
    'audit.detailTitle': '审计详情',
    'audit.extraDetailTitle': '附加详情',
    'audit.noExtraDetail': '无附加详情',

    // 反馈
    'audit.loadFailed': '审计日志加载失败'
  },
  en: {
    'audit.title': 'Audit Log',
    'audit.subtitle':
      'Audit entries record the signed-in user and request IP; write failures do not affect the main flow',
    'audit.actorPlaceholder': 'Actor',
    'audit.allActions': 'All actions',
    'audit.search': 'Search',
    'audit.listHint': 'Sorted by time (newest first); click any row for full details.',

    // Table columns
    'audit.time': 'Time',
    'audit.actor': 'Actor',
    'audit.action': 'Action',
    'audit.target': 'Target',
    'audit.emptyTitle': 'No audit entries yet',
    'audit.emptyHint':
      'Sign-ins, user management, recording processing, reviews and configuration changes are recorded automatically',

    // Details
    'audit.detailTitle': 'Audit Details',
    'audit.extraDetailTitle': 'Additional details',
    'audit.noExtraDetail': 'No additional details',

    // Feedback
    'audit.loadFailed': 'Failed to load audit log'
  }
}
