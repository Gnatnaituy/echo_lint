/**
 * 菜单目录（前端唯一来源）
 *
 * - `key` 必须与后端 `com.echolint.domain.MenuKey` 枚举名严格一致（后端按角色校验绑定）；
 * - 侧栏渲染、路由守卫、用户管理「菜单绑定」共用本目录，避免三处各写一份；
 * - `adminOnly` 与后端枚举保持一致：非 ADMIN 账号的绑定范围只包含非 adminOnly 菜单；
 * - 菜单目录只保留后端角色允许的「上限」，用户绑定是在此范围内的收窄，
 *   不会放大权限——接口权限仍由后端按角色的 URL 矩阵独立校验。
 */

export const MENU_GROUPS = [
  {
    titleKey: 'nav.group.auditOps',
    items: [
      { key: 'DASHBOARD', path: '/', labelKey: 'nav.dashboard' },
      { key: 'RECORDINGS', path: '/recordings', labelKey: 'nav.recordings' },
      { key: 'REPORTS', path: '/reports', labelKey: 'nav.reports' },
      { key: 'REVIEW', path: '/review', labelKey: 'nav.review', badge: true },
      // 全屏页（不渲染侧栏/页头）：不作为登录落地页的默认目标
      { key: 'SCREEN', path: '/screen', labelKey: 'nav.screen', fullscreen: true }
    ]
  },
  {
    titleKey: 'nav.group.knowledge',
    items: [
      { key: 'DICTIONARY', path: '/dictionary', labelKey: 'nav.dictionary' },
      { key: 'CORPUS', path: '/corpus', labelKey: 'nav.corpus' },
      { key: 'KNOWLEDGE', path: '/knowledge', labelKey: 'nav.knowledge' }
    ]
  },
  {
    titleKey: 'nav.group.system',
    items: [
      { key: 'SETTINGS', path: '/settings', labelKey: 'nav.settings' },
      { key: 'COSTS', path: '/costs', labelKey: 'nav.costs', adminOnly: true },
      { key: 'TASKS', path: '/tasks', labelKey: 'nav.tasks', adminOnly: true },
      { key: 'AI_CHAT', path: '/ai-chat', labelKey: 'nav.aiChat', adminOnly: true },
      { key: 'API_KEYS', path: '/api-keys', labelKey: 'nav.apiKeys', adminOnly: true },
      { key: 'USERS', path: '/users', labelKey: 'nav.users', adminOnly: true },
      { key: 'AUDIT', path: '/audit', labelKey: 'nav.audit', adminOnly: true }
    ]
  }
]

/** 扁平化菜单项（保持目录顺序，枚举顺序一致） */
export const MENU_ITEMS = MENU_GROUPS.flatMap((group) => group.items)

/** key → 菜单项 */
export const MENU_BY_KEY = Object.fromEntries(MENU_ITEMS.map((item) => [item.key, item]))

/** 全部合法菜单 key */
export const MENU_KEYS = MENU_ITEMS.map((item) => item.key)

const MENU_KEY_SET = new Set(MENU_KEYS)

/** 菜单绑定时角色允许的「全集」：ADMIN 全部；REVIEWER / VIEWER 仅非 adminOnly 菜单 */
export function menuKeysForRole(role) {
  return MENU_ITEMS.filter((item) => role === 'ADMIN' || !item.adminOnly).map((item) => item.key)
}

/**
 * 当前用户生效菜单：
 * - 后端 `/api/auth/me` 下发的 `menuKeys` 优先（已按角色过滤）；
 * - 缺失 / 空数组（旧后端、异常数据）回退为角色默认全集，避免把用户锁在空白侧栏。
 * 未知 key（前端尚未支持的新菜单）会被忽略，不影响其余菜单。
 */
export function resolveVisibleMenuKeys(user) {
  if (!user) return []
  const bound = Array.isArray(user.menuKeys)
    ? user.menuKeys.filter((key) => MENU_KEY_SET.has(key))
    : null
  if (bound && bound.length) return bound
  return menuKeysForRole(user.role)
}

/** 侧栏分组：过滤掉不可见菜单项，并移除空分组 */
export function visibleMenuGroups(user) {
  const visible = new Set(resolveVisibleMenuKeys(user))
  return MENU_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => visible.has(item.key))
  })).filter((group) => group.items.length > 0)
}

/**
 * 首页路径（登录落地 / 无权限兜底）：
 * - 目录顺序里第一个可见菜单，但全屏页（数据大屏）不作为落地页——它不渲染侧栏/页头，
 *   直接落地会让「退出大屏」回到 `/` 又被弹回自身；
 * - 仅当全屏页是唯一可见菜单时才回退到它；无任何可见菜单时回退 `/`。
 */
export function firstAllowedPath(user) {
  const visible = new Set(resolveVisibleMenuKeys(user))
  const items = MENU_ITEMS.filter((item) => visible.has(item.key))
  const landing = items.find((item) => !item.fullscreen) || items[0]
  return landing ? landing.path : '/'
}

/**
 * 路由菜单门禁决策（纯函数，便于单测；router 守卫直接消费返回值）：
 * - 路由无 menuKey 或菜单已可见 → null（放行）；
 * - 登录落地页 `/` 被绑定掉（例如账号只绑了录音/复检）→ 静默跳首个可见菜单，不弹「未绑定」；
 * - 其他页面 → 提示无菜单权限后跳首个可见菜单；
 * - 极端数据下首个可见菜单恰好就是当前目标 → 返回 stay（中止导航），换取「不重定向到自己」的安全。
 *
 * @param {string} toPath 目标路径
 * @param {string|undefined} menuKey 目标路由的菜单 key
 * @param {object|null} user 当前登录用户
 * @returns {null | { path?: string, notify?: boolean, stay?: boolean }}
 */
export function menuGateDecision(toPath, menuKey, user) {
  if (!menuKey) return null
  const visible = resolveVisibleMenuKeys(user)
  if (visible.includes(menuKey)) return null
  const homePath = firstAllowedPath(user)
  if (homePath === toPath) return { stay: true }
  return { path: homePath, notify: toPath !== '/' }
}

/**
 * 角色切换时的菜单选择联动（用户管理弹窗）：
 * - 原来已全选旧角色允许项（= 用户预期「跟随角色」）→ 全选新角色允许项；
 * - 否则保留与新角色允许项的交集；交集为空时回填新角色允许项，避免冻结在「一个都没选」。
 *
 * @param {string[]} selected 当前已选 key
 * @param {string[]} previousAllowed 旧角色允许项
 * @param {string[]} nextAllowed 新角色允许项
 * @returns {string[]} 调整后的已选 key
 */
export function reconcileMenuSelection(selected, previousAllowed, nextAllowed) {
  const selectedSet = new Set(selected)
  const wasAllSelected =
    previousAllowed.length > 0 && previousAllowed.every((key) => selectedSet.has(key))
  const kept = selected.filter((key) => nextAllowed.includes(key))
  return wasAllSelected || kept.length === 0 ? [...nextAllowed] : kept
}
