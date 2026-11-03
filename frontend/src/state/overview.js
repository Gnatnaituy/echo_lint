/**
 * 全局概览统计（单例）
 *
 * `GET /api/stats/overview` 被多处消费：App.vue 15s 全局轮询（侧栏待复检角标 / 后端健康态）、
 * 工作台 KPI、语料库 / 敏感词库的概览卡片。各页面此前各发一份请求，路由切换时会重复打后端。
 *
 * 这里按 `auth.js` 的单例风格收口：
 * - 请求去重：并发调用复用一个 in-flight Promise（App 轮询与页面挂载天然错峰并发）
 * - 短缓存：默认 15s 内复用最近一次成功结果（与 App 轮询同频，页面切回不重复请求）
 * - 失败保留旧数据：后台轮询失败时不清空界面，由 error 状态与调用方决定展示
 *
 * 非 Vue 依赖注入版工厂 `createOverviewState` 单独导出，便于 node:test 直接验证
 * 去重 / 缓存 / 失败语义（见 tests/api.test.mjs）。
 */
import { reactive } from 'vue'
import api from '../api/index.js'

/** 缓存新鲜期：与 App.vue 全局轮询间隔一致（15s），页面挂载优先复用刚拉到的数据 */
export const OVERVIEW_TTL_MS = 15000

/**
 * 创建一个概览状态容器。
 * @param {() => Promise<object>} fetcher 实际请求函数（生产为 api.overview）
 * @param {{ttlMs?: number, now?: () => number}} [options] 缓存新鲜期与时钟（单测注入）
 */
export function createOverviewState(
  fetcher,
  { ttlMs = OVERVIEW_TTL_MS, now = () => Date.now() } = {}
) {
  if (typeof fetcher !== 'function') {
    throw new TypeError('createOverviewState(fetcher) requires a fetcher function')
  }
  const state = reactive({
    /** 最近一次成功响应的概览数据（失败时保留上一次成功值） */
    data: null,
    /** 是否有请求在途（含复用的并发请求） */
    loading: false,
    /** 最近一次失败（成功或主动 reset 后清空） */
    error: null,
    /** 最近一次成功时间戳，用于缓存新鲜期判断 */
    loadedAt: 0
  })

  let inflight = null

  function apply(data) {
    state.data = data && typeof data === 'object' ? data : null
    state.loadedAt = now()
    state.error = null
    return state.data
  }

  /**
   * 加载概览。
   *
   * - 并发调用直接复用同一个 in-flight 请求（去重）
   * - 未传 force 且缓存未过期时返回缓存（多页面共享同一份数据）
   * - 失败时抛错并记录 error，但保留旧 data（后台轮询失败不清空界面）
   *
   * @param {{force?: boolean}} [options] force=true 忽略缓存强制刷新（轮询 / 写操作后）
   */
  function load({ force = false } = {}) {
    if (inflight) return inflight
    if (!force && state.data && now() - state.loadedAt < ttlMs) {
      return Promise.resolve(state.data)
    }
    state.loading = true
    inflight = (async () => {
      try {
        return apply(await fetcher())
      } catch (error) {
        state.error = error instanceof Error ? error : new Error(String(error))
        throw state.error
      } finally {
        state.loading = false
        inflight = null
      }
    })()
    return inflight
  }

  /** 数据可能已变化（新增 / 删除 / 编辑）：让下一次 load 重新请求 */
  function invalidate() {
    state.loadedAt = 0
    state.error = null
  }

  /** 清空缓存与错误（登出 / 切换账号时使用） */
  function reset() {
    state.data = null
    state.loadedAt = 0
    state.error = null
  }

  return {
    get data() {
      return state.data
    },
    get loading() {
      return state.loading
    },
    get error() {
      return state.error
    },
    get loadedAt() {
      return state.loadedAt
    },
    load,
    invalidate,
    reset
  }
}

const overviewState = createOverviewState(() => api.overview())

/** 全局单例入口（App.vue / Dashboard / Corpus / Dictionary 共用） */
export default overviewState

export const useOverview = () => overviewState
