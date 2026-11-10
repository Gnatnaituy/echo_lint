import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import auth from '../auth'
import { t } from '../i18n'
import { menuGateDecision } from '../menu.js'

// titleKey 用于 i18n 路由标题（App.vue / router.afterEach 按当前语言翻译）
// menuKey 与 frontend/src/menu.js 及后端 MenuKey 枚举一一对应：菜单绑定后不可见的页面直接拦截
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
    meta: { titleKey: 'nav.dashboard', menuKey: 'DASHBOARD' }
  },
  {
    path: '/recordings',
    component: () => import('../views/Recordings.vue'),
    meta: { titleKey: 'nav.recordings', menuKey: 'RECORDINGS' }
  },
  {
    path: '/reports',
    component: () => import('../views/Reports.vue'),
    meta: { titleKey: 'nav.reports', menuKey: 'REPORTS' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { titleKey: 'nav.review', menuKey: 'REVIEW' }
  },
  {
    path: '/dictionary',
    component: () => import('../views/Dictionary.vue'),
    meta: { titleKey: 'nav.dictionary', menuKey: 'DICTIONARY' }
  },
  {
    path: '/corpus',
    component: () => import('../views/Corpus.vue'),
    meta: { titleKey: 'nav.corpus', menuKey: 'CORPUS' }
  },
  {
    path: '/users',
    component: () => import('../views/Users.vue'),
    meta: { titleKey: 'nav.users', roles: ['ADMIN'], menuKey: 'USERS' }
  },
  {
    path: '/audit',
    component: () => import('../views/Audit.vue'),
    meta: { titleKey: 'nav.audit', roles: ['ADMIN'], menuKey: 'AUDIT' }
  },
  {
    // AI 用量与成本看板：仅管理员（后端接口同样 ADMIN 校验）
    path: '/costs',
    component: () => import('../views/Costs.vue'),
    meta: { titleKey: 'nav.costs', roles: ['ADMIN'], menuKey: 'COSTS' }
  },
  {
    // 异步任务队列（流水线任务持久化 / 退避重试 / 死信）：仅管理员
    path: '/tasks',
    component: () => import('../views/Tasks.vue'),
    meta: { titleKey: 'nav.tasks', roles: ['ADMIN'], menuKey: 'TASKS' }
  },
  {
    // AI 对话记录（提示词/原始回复/用量留痕）：仅管理员
    path: '/ai-chat',
    component: () => import('../views/AiChat.vue'),
    meta: { titleKey: 'nav.aiChat', roles: ['ADMIN'], menuKey: 'AI_CHAT' }
  },
  {
    // 开放 API 密钥管理：仅管理员
    path: '/api-keys',
    component: () => import('../views/ApiKeys.vue'),
    meta: { titleKey: 'nav.apiKeys', roles: ['ADMIN'], menuKey: 'API_KEYS' }
  },
  {
    // 知识库 / SOP：所有登录用户可读，写操作由页面按角色隐藏（后端独立鉴权）
    path: '/knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { titleKey: 'nav.knowledge', menuKey: 'KNOWLEDGE' }
  },
  {
    // 索引明细已并入知识库页（页内 Tab，仅 ADMIN 可见）：旧地址保留跳转，避免书签失效
    path: '/knowledge-index',
    redirect: { path: '/knowledge', query: { tab: 'index' } },
    meta: { titleKey: 'nav.knowledge', menuKey: 'KNOWLEDGE' }
  },
  {
    // 数据大屏：登录后可用；fullscreen 表示不渲染侧栏/页头壳层
    path: '/screen',
    component: () => import('../views/Screen.vue'),
    meta: { titleKey: 'nav.screen', fullscreen: true, menuKey: 'SCREEN' }
  },
  {
    // 系统设置拆为子路由 + 二级导航（SettingsLayout 承载页头 / 导航 / router-view）；
    // 旧链接 /settings 保留重定向到 /settings/screening，并保留 query
    path: '/settings',
    component: () => import('../views/SettingsLayout.vue'),
    redirect: (to) => ({ path: '/settings/screening', query: to.query }),
    meta: { titleKey: 'nav.settings', menuKey: 'SETTINGS' },
    children: [
      {
        // 复筛模型：所有登录用户可查看，写操作由页内 canWrite 隐藏（后端独立鉴权）
        path: 'screening',
        component: () => import('../views/settings/ScreeningSettings.vue'),
        meta: {
          titleKey: 'settings.nav.screening',
          descKey: 'settings.page.screeningDesc',
          menuKey: 'SETTINGS'
        }
      },
      {
        // 语音转写（ASR provider 切换 + 配置 + 连通性测试）：仅 ADMIN
        path: 'transcription',
        component: () => import('../views/settings/TranscriptionSettings.vue'),
        meta: {
          titleKey: 'settings.nav.transcription',
          descKey: 'settings.page.transcriptionDesc',
          menuKey: 'SETTINGS',
          roles: ['ADMIN']
        }
      },
      {
        // 知识增强（RAG）：仅 ADMIN
        path: 'rag',
        component: () => import('../views/settings/RagSettings.vue'),
        meta: {
          titleKey: 'settings.nav.rag',
          descKey: 'settings.page.ragDesc',
          menuKey: 'SETTINGS',
          roles: ['ADMIN']
        }
      },
      {
        // 通知与抽样：仅 ADMIN
        path: 'notifications',
        component: () => import('../views/settings/NotificationSettings.vue'),
        meta: {
          titleKey: 'settings.nav.notifications',
          descKey: 'settings.page.notificationsDesc',
          menuKey: 'SETTINGS',
          roles: ['ADMIN']
        }
      },
      {
        // 运行环境：所有登录用户（ScopeCard 内容 + ASR 环境变量说明）
        path: 'runtime',
        component: () => import('../views/settings/RuntimeSettings.vue'),
        meta: {
          titleKey: 'settings.nav.runtime',
          descKey: 'settings.page.runtimeDesc',
          menuKey: 'SETTINGS'
        }
      }
    ]
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
 * - meta.roles 命中当前角色才可进入，否则提示并回首页
 * - meta.menuKey 命中用户菜单绑定（角色允许 ∩ 用户绑定）才可进入，否则提示并跳首个可见菜单；
 *   首页 `/` 本身也可能被绑定掉，所以「回首页」统一走 auth.homePath，避免重定向死循环
 */
router.beforeEach(async (to) => {
  if (to.meta?.public) {
    if (to.path === '/login' && auth.user) return { path: auth.homePath }
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
    return { path: auth.homePath }
  }

  // 菜单门禁：决策收敛在 menu.js（纯函数，便于单测）。
  // 登录落地 `/` 被绑定掉时静默跳首个可见菜单；直接访问未绑定页面才提示。
  const gate = menuGateDecision(to.path, to.meta?.menuKey, auth.user)
  if (gate) {
    if (gate.notify) ElMessage.error(t('auth.noMenuPermission'))
    // stay=true：首菜单与目标相同（极端数据），中止导航而不是重定向到自己
    return gate.stay ? false : { path: gate.path }
  }

  return true
})

router.afterEach((to) => {
  // 路由标题跟随语言
  const title = to.meta?.titleKey ? t(to.meta.titleKey) : ''
  document.title = title ? `${title} · ${t('app.name')}` : `${t('app.name')} · ${t('app.subtitle')}`
})

export default router
