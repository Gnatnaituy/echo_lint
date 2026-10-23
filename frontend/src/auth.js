/**
 * 认证状态（单例）
 *
 * 会话由后端下发的 HttpOnly JSESSIONID 维持，前端不保存 token / 密码：
 * - 启动或刷新页面时由路由守卫调用 fetchMe() 通过 GET /api/auth/me 恢复会话
 * - 401 由 axios 拦截器统一清理状态并跳转 /login
 * - 角色仅用于前端展示与隐藏入口，后端会独立鉴权
 */
import { reactive } from 'vue'
import api from './api'
import { ROLE_META } from './constants'
import { t } from './i18n/index.js'

const state = reactive({
  /** 当前登录用户：{id, username, displayName, role}；null 表示未登录 */
  user: null,
  /** 登录 / 会话恢复请求进行中 */
  loading: false,
  /** 是否已完成一次会话恢复（成功或失败） */
  initialized: false
})

function setUser(user) {
  state.user = user && typeof user === 'object' ? user : null
}

function clear() {
  state.user = null
  state.initialized = true
}

function hasRole(...roles) {
  const list = roles.flat(Infinity).filter(Boolean)
  if (!list.length) return true
  return !!state.user?.role && list.includes(state.user.role)
}

/** 供路由守卫复用的会话恢复请求去重 */
let inflightFetch = null

export const auth = {
  get user() {
    return state.user
  },
  get username() {
    return state.user?.username || ''
  },
  get displayName() {
    return state.user?.displayName || state.user?.username || ''
  },
  get role() {
    return state.user?.role || ''
  },
  get roleMeta() {
    return ROLE_META[state.user?.role] || null
  },
  get roleLabel() {
    return ROLE_META[state.user?.role]?.label || (state.user?.role ? String(state.user.role) : t('auth.notLoggedIn'))
  },
  get isAuthenticated() {
    return !!state.user
  },
  get loading() {
    return state.loading
  },
  get initialized() {
    return state.initialized
  },

  hasRole,
  isAdmin() {
    return hasRole('ADMIN')
  },
  /** 可执行复核类写操作（ADMIN / REVIEWER） */
  canReview() {
    return hasRole('ADMIN', 'REVIEWER')
  },

  /** 登录：成功写入当前用户并返回；失败抛出带后端 message 的 Error */
  async login(username, password) {
    state.loading = true
    try {
      const user = await api.login({
        username: String(username || '').trim(),
        password: String(password || '')
      })
      setUser(user)
      state.initialized = true
      return user
    } finally {
      state.loading = false
    }
  },

  /** 退出登录：即使后端会话已失效也清空本地状态 */
  async logout() {
    try {
      await api.logout()
    } catch {
      // 忽略：会话可能已过期，本地状态仍需清理
    } finally {
      clear()
    }
  },

  /**
   * 恢复会话：GET /api/auth/me。
   * 已登录时直接返回缓存用户（force 可强制刷新）；并发调用复用同一请求。
   */
  async fetchMe({ force = false } = {}) {
    if (state.user && !force) return state.user
    if (inflightFetch) return inflightFetch

    state.loading = true
    inflightFetch = (async () => {
      try {
        const user = await api.me()
        setUser(user)
        return state.user
      } catch {
        clear()
        return null
      } finally {
        state.loading = false
        state.initialized = true
        inflightFetch = null
      }
    })()
    return inflightFetch
  },

  clear
}

export function useAuth() {
  return auth
}

export default auth
