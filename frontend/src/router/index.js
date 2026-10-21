import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import auth from '../auth'
import { t } from '../i18n'

// titleKey 用于 i18n 路由标题；title 为中文回退（旧代码/无 i18n 时可用）
const routes = [
  {
    // 登录页：独立布局（不套壳层），已登录用户由守卫送回工作台
    path: '/login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', titleKey: 'nav.login', public: true }
  },
  {
    path: '/',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '工作台', titleKey: 'nav.dashboard' }
  },
  {
    path: '/recordings',
    component: () => import('../views/Recordings.vue'),
    meta: { title: '录音管理', titleKey: 'nav.recordings' }
  },
  {
    path: '/reports',
    component: () => import('../views/Reports.vue'),
    meta: { title: '报表', titleKey: 'nav.reports' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { title: '人工复检', titleKey: 'nav.review' }
  },
  {
    path: '/dictionary',
    component: () => import('../views/Dictionary.vue'),
    meta: { title: '敏感词库', titleKey: 'nav.dictionary' }
  },
  {
    path: '/corpus',
    component: () => import('../views/Corpus.vue'),
    meta: { title: '语料库', titleKey: 'nav.corpus' }
  },
  {
    path: '/users',
    component: () => import('../views/Users.vue'),
    meta: { title: '用户管理', titleKey: 'nav.users', roles: ['ADMIN'] }
  },
  {
    path: '/audit',
    component: () => import('../views/Audit.vue'),
    meta: { title: '操作审计', titleKey: 'nav.audit', roles: ['ADMIN'] }
  },
  {
    // AI 用量与成本看板：仅管理员（后端接口同样 ADMIN 校验）
    path: '/costs',
    component: () => import('../views/Costs.vue'),
    meta: { title: '成本看板', titleKey: 'nav.costs', roles: ['ADMIN'] }
  },
  {
    // 异步任务队列（流水线任务持久化 / 退避重试 / 死信）：仅管理员
    path: '/tasks',
    component: () => import('../views/Tasks.vue'),
    meta: { title: '任务队列', titleKey: 'nav.tasks', roles: ['ADMIN'] }
  },
  {
    // AI 对话记录（提示词/原始回复/用量留痕）：仅管理员
    path: '/ai-chat',
    component: () => import('../views/AiChat.vue'),
    meta: { title: 'AI 对话记录', titleKey: 'nav.aiChat', roles: ['ADMIN'] }
  },
  {
    // 开放 API 密钥管理：仅管理员
    path: '/api-keys',
    component: () => import('../views/ApiKeys.vue'),
    meta: { title: 'API 密钥', titleKey: 'nav.apiKeys', roles: ['ADMIN'] }
  },
  {
    // 知识库 / SOP：所有登录用户可读，写操作由页面按角色隐藏（后端独立鉴权）
    path: '/knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { title: '知识库', titleKey: 'nav.knowledge' }
  },
  {
    // 数据大屏：登录后可用；fullscreen 表示不渲染侧栏/页头壳层
    path: '/screen',
    component: () => import('../views/Screen.vue'),
    meta: { title: '数据大屏', titleKey: 'nav.screen', fullscreen: true }
  },
  {
    path: '/settings',
    component: () => import('../views/Settings.vue'),
    meta: { title: '系统设置', titleKey: 'nav.settings' }
  },
  {
    // 兜底：未知路径（例如被外部容器/地址栏拼坏的 /blank）一律回到工作台，
    // 避免出现「只剩侧栏与顶栏、正文空白」的状态，同时把 URL 自愈为 /
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局守卫：
 * - 公开路由（登录页）直接放行；已登录访问 /login 则回工作台
 * - 未登录先尝试 /api/auth/me 恢复会话（刷新页面/服务端会话仍有效时无需重登）
 * - 会话恢复失败 → /login?redirect=<原地址>
 * - meta.roles 命中当前角色才可进入，否则提示并回工作台
 */
router.beforeEach(async (to) => {
  if (to.meta?.public) {
    if (to.path === '/login' && auth.user) return { path: '/' }
    return true
  }

  if (!auth.user) {
    const user = await auth.fetchMe()
    if (!user) {
      const full = to.fullPath || ''
      const redirect = full && full !== '/' && !full.startsWith('/login') ? full : undefined
      return { path: '/login', query: redirect ? { redirect } : {} }
    }
  }

  const roles = to.meta?.roles
  if (Array.isArray(roles) && roles.length && !auth.hasRole(...roles)) {
    ElMessage.error('无权限访问该页面')
    return { path: '/' }
  }

  return true
})

router.afterEach((to) => {
  // 路由标题跟随语言：titleKey 优先，缺失时回退 title
  const title = to.meta?.titleKey ? t(to.meta.titleKey) : to.meta?.title
  document.title = title ? `${title} · ${t('app.name')}` : `${t('app.name')} · ${t('app.subtitle')}`
})

export default router
