import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import auth from '../auth'
import { t } from '../i18n'

// titleKey 用于 i18n 路由标题（App.vue / router.afterEach 按当前语言翻译）
const routes = [
  {
    // 登录页：独立布局（不套壳层），已登录用户由守卫送回工作台
    path: '/login',
    component: () => import('../views/Login.vue'),
    meta: { titleKey: 'nav.login', public: true }
  },
  {
    path: '/',
    component: () => import('../views/Dashboard.vue'),
    meta: { titleKey: 'nav.dashboard' }
  },
  {
    path: '/recordings',
    component: () => import('../views/Recordings.vue'),
    meta: { titleKey: 'nav.recordings' }
  },
  {
    path: '/reports',
    component: () => import('../views/Reports.vue'),
    meta: { titleKey: 'nav.reports' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { titleKey: 'nav.review' }
  },
  {
    path: '/dictionary',
    component: () => import('../views/Dictionary.vue'),
    meta: { titleKey: 'nav.dictionary' }
  },
  {
    path: '/corpus',
    component: () => import('../views/Corpus.vue'),
    meta: { titleKey: 'nav.corpus' }
  },
  {
    path: '/users',
    component: () => import('../views/Users.vue'),
    meta: { titleKey: 'nav.users', roles: ['ADMIN'] }
  },
  {
    path: '/audit',
    component: () => import('../views/Audit.vue'),
    meta: { titleKey: 'nav.audit', roles: ['ADMIN'] }
  },
  {
    // AI 用量与成本看板：仅管理员（后端接口同样 ADMIN 校验）
    path: '/costs',
    component: () => import('../views/Costs.vue'),
    meta: { titleKey: 'nav.costs', roles: ['ADMIN'] }
  },
  {
    // 异步任务队列（流水线任务持久化 / 退避重试 / 死信）：仅管理员
    path: '/tasks',
    component: () => import('../views/Tasks.vue'),
    meta: { titleKey: 'nav.tasks', roles: ['ADMIN'] }
  },
  {
    // AI 对话记录（提示词/原始回复/用量留痕）：仅管理员
    path: '/ai-chat',
    component: () => import('../views/AiChat.vue'),
    meta: { titleKey: 'nav.aiChat', roles: ['ADMIN'] }
  },
  {
    // 开放 API 密钥管理：仅管理员
    path: '/api-keys',
    component: () => import('../views/ApiKeys.vue'),
    meta: { titleKey: 'nav.apiKeys', roles: ['ADMIN'] }
  },
  {
    // 知识库 / SOP：所有登录用户可读，写操作由页面按角色隐藏（后端独立鉴权）
    path: '/knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { titleKey: 'nav.knowledge' }
  },
  {
    // 索引明细已并入知识库页（页内 Tab，仅 ADMIN 可见）：旧地址保留跳转，避免书签失效
    path: '/knowledge-index',
    redirect: { path: '/knowledge', query: { tab: 'index' } },
    meta: { titleKey: 'nav.knowledge' }
  },
  {
    // 数据大屏：登录后可用；fullscreen 表示不渲染侧栏/页头壳层
    path: '/screen',
    component: () => import('../views/Screen.vue'),
    meta: { titleKey: 'nav.screen', fullscreen: true }
  },
  {
    path: '/settings',
    component: () => import('../views/Settings.vue'),
    meta: { titleKey: 'nav.settings' }
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
    ElMessage.error(t('auth.noPermissionPage'))
    return { path: '/' }
  }

  return true
})

router.afterEach((to) => {
  // 路由标题跟随语言
  const title = to.meta?.titleKey ? t(to.meta.titleKey) : ''
  document.title = title ? `${title} · ${t('app.name')}` : `${t('app.name')} · ${t('app.subtitle')}`
})

export default router
