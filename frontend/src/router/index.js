import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import auth from '../auth'

const routes = [
  {
    // 登录页：独立布局（不套壳层），已登录用户由守卫送回工作台
    path: '/login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '工作台' }
  },
  {
    path: '/recordings',
    component: () => import('../views/Recordings.vue'),
    meta: { title: '录音管理' }
  },
  {
    path: '/reports',
    component: () => import('../views/Reports.vue'),
    meta: { title: '报表' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { title: '人工复检' }
  },
  {
    path: '/dictionary',
    component: () => import('../views/Dictionary.vue'),
    meta: { title: '敏感词库' }
  },
  {
    path: '/corpus',
    component: () => import('../views/Corpus.vue'),
    meta: { title: '语料库' }
  },
  {
    path: '/users',
    component: () => import('../views/Users.vue'),
    meta: { title: '用户管理', roles: ['ADMIN'] }
  },
  {
    path: '/audit',
    component: () => import('../views/Audit.vue'),
    meta: { title: '操作审计', roles: ['ADMIN'] }
  },
  {
    // AI 用量与成本看板：仅管理员（后端接口同样 ADMIN 校验）
    path: '/costs',
    component: () => import('../views/Costs.vue'),
    meta: { title: '成本看板', roles: ['ADMIN'] }
  },
  {
    path: '/settings',
    component: () => import('../views/Settings.vue'),
    meta: { title: '系统设置' }
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
  document.title = to.meta?.title ? `${to.meta.title} · EchoLint` : 'EchoLint · 录音稽核平台'
})

export default router
