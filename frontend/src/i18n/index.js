/**
 * 轻量 i18n（无第三方依赖）。
 *
 * - `locale` 是 reactive 状态：模板里调用 `t()` 会在语言切换时自动重渲染。
 * - `t(key, params)`：当前语言 → 中文回退 → 原样返回 key；支持 `{name}` 插值。
 * - `setLocale(locale)`：切换并写入 localStorage（key: echolint.locale），同步 <html lang>。
 * - `useI18n()`：组件内使用的组合式入口。
 *
 * 当前覆盖范围（增量迁移）：页头 / 侧栏导航 / 登录页 / 路由标题 / 通用按钮 / 数据大屏；
 * 其它业务页面仍为中文，未翻译 key 自动回退中文（符合 Batch 4b 的增量迁移约定）。
 */

import { computed, reactive } from 'vue'
import zh from './zh.js'
import en from './en.js'

export const LOCALE_STORAGE_KEY = 'echolint.locale'
export const MESSAGES = { zh, en }
export const AVAILABLE_LOCALES = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'EN' }
]

/** 读取持久化语言；无 localStorage / 无 window 时默认中文（服务端与单测环境安全） */
export function detectInitialLocale() {
  try {
    if (typeof localStorage !== 'undefined') {
      const saved = localStorage.getItem(LOCALE_STORAGE_KEY)
      if (saved && MESSAGES[saved]) return saved
    }
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用，忽略
  }
  try {
    if (
      typeof window !== 'undefined' &&
      typeof navigator !== 'undefined' &&
      String(navigator.language || '').toLowerCase().startsWith('en')
    ) {
      return 'en'
    }
  } catch {
    // ignore
  }
  return 'zh'
}

const state = reactive({ locale: detectInitialLocale() })

/** 同步 <html lang>，便于屏幕阅读器与 CSS :lang() */
export function applyDocumentLang(locale = state.locale) {
  try {
    if (typeof document !== 'undefined') {
      document.documentElement.lang = locale === 'zh' ? 'zh-CN' : 'en'
    }
  } catch {
    // ignore
  }
}

/** 简单占位符插值：{name} -> params.name；缺参数时保留占位符 */
export function interpolate(template, params) {
  if (params == null) return String(template)
  return String(template).replace(/\{(\w+)\}/g, (match, key) => {
    const value = params[key]
    return value === undefined || value === null ? match : String(value)
  })
}

export function getLocale() {
  return state.locale
}

/** 翻译：当前语言缺失 -> 中文 -> key 本身 */
export function t(key, params) {
  if (!key) return ''
  const current = MESSAGES[state.locale] || MESSAGES.zh
  const raw = current[key] ?? MESSAGES.zh[key]
  return interpolate(raw === undefined ? key : raw, params)
}

/** 切换语言：非法值回退中文；持久化并同步 document.lang */
export function setLocale(locale) {
  const next = MESSAGES[locale] ? locale : 'zh'
  state.locale = next
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(LOCALE_STORAGE_KEY, next)
  } catch {
    // ignore
  }
  applyDocumentLang(next)
  return next
}

export function toggleLocale() {
  return setLocale(state.locale === 'zh' ? 'en' : 'zh')
}

/** 组合式入口：locale 为 computed，便于 watch */
export function useI18n() {
  return {
    locale: computed(() => state.locale),
    t,
    setLocale,
    getLocale,
    toggleLocale,
    availableLocales: AVAILABLE_LOCALES
  }
}

/** 词典 key 列表（用于单测 key 对齐） */
export function messageKeys(dict) {
  return Object.keys(dict || {}).sort()
}

// 首次加载即同步 <html lang>（浏览器环境）
applyDocumentLang()
