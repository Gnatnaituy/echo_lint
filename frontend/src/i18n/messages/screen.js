/**
 * screen 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `screen.` 前缀。
 * 注意：大屏主体文案（标题 / KPI / 面板标题 / 刷新提示等）在 base.js 的
 * `screen.*` 下维护，本模块只补充大屏错误提示与大屏工具函数兜底文案。
 */
export default {
  zh: {
    'screen.forbidden': '当前账号无权限查看数据大屏（请重新登录）',
    'screen.apiUnavailable': '后端统计接口不可用（HTTP 404），请确认后端版本',
    'screen.loadFailed': '数据加载失败，请稍后重试',
    'screen.notFilled': '未填写'
  },
  en: {
    'screen.forbidden': 'You do not have permission to view the big screen. Please sign in again.',
    'screen.apiUnavailable':
      'The backend statistics endpoint is unavailable (HTTP 404). Please verify the backend version.',
    'screen.loadFailed': 'Failed to load data. Please try again later.',
    'screen.notFilled': 'Not provided'
  }
}
