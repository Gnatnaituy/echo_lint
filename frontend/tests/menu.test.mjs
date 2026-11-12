/**
 * 菜单目录与「用户菜单绑定」纯逻辑单测。
 *
 * 守卫目标：
 * - 前端菜单 key / 顺序契约稳定（后端 MenuKey 枚举按同一顺序返回）；
 * - 侧栏、路由 meta、用户管理绑定三处共用一份目录，路由漏配 menuKey 会在这里失败；
 * - 菜单绑定的收窄 / 回退语义（未知 key 忽略、空绑定回退角色默认、首页不再固定 `/`）。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import zh from '../src/i18n/zh.js'
import en from '../src/i18n/en.js'
import {
  MENU_BY_KEY,
  MENU_GROUPS,
  MENU_ITEMS,
  MENU_KEYS,
  firstAllowedPath,
  menuGateDecision,
  menuKeysForRole,
  reconcileMenuSelection,
  resolveVisibleMenuKeys,
  visibleMenuGroups
} from '../src/menu.js'

const ADMIN_ONLY_KEYS = ['COSTS', 'TASKS', 'AI_CHAT', 'API_KEYS', 'USERS', 'AUDIT']
const NON_ADMIN_KEYS = MENU_KEYS.filter((key) => !ADMIN_ONLY_KEYS.includes(key))

test('菜单目录：key 顺序与后端 MenuKey 枚举一致，key / path 全局唯一', () => {
  assert.deepEqual(MENU_KEYS, [
    'DASHBOARD',
    'RECORDINGS',
    'REVIEW',
    'REPORTS',
    'SCREEN',
    'DICTIONARY',
    'CORPUS',
    'KNOWLEDGE',
    'SETTINGS',
    'COSTS',
    'TASKS',
    'AI_CHAT',
    'API_KEYS',
    'USERS',
    'AUDIT'
  ])
  assert.equal(new Set(MENU_KEYS).size, MENU_KEYS.length)
  assert.equal(new Set(MENU_ITEMS.map((item) => item.path)).size, MENU_ITEMS.length)
  assert.deepEqual(
    MENU_ITEMS.filter((item) => item.adminOnly).map((item) => item.key),
    ADMIN_ONLY_KEYS
  )
  assert.equal(MENU_GROUPS.flatMap((group) => group.items).length, MENU_ITEMS.length)
})

test('菜单目录：所有分组标题 / 菜单文案在 zh 与 en 词典中都有 key', () => {
  for (const group of MENU_GROUPS) {
    assert.ok(group.titleKey in zh, `缺少中文分组文案: ${group.titleKey}`)
    assert.ok(group.titleKey in en, `缺少英文分组文案: ${group.titleKey}`)
  }
  for (const item of MENU_ITEMS) {
    assert.ok(item.labelKey in zh, `缺少中文菜单文案: ${item.labelKey}`)
    assert.ok(item.labelKey in en, `缺少英文菜单文案: ${item.labelKey}`)
    assert.ok(MENU_BY_KEY[item.key], `MENU_BY_KEY 缺少 ${item.key}`)
  }
})

test('路由一致性：每个菜单项都有 path + meta.menuKey，且没有遗漏 / 多余的 menuKey', () => {
  const source = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const routePaths = new Set([...source.matchAll(/path:\s*'([^']+)'/g)].map((match) => match[1]))
  const routeMenuKeys = new Set(
    [...source.matchAll(/menuKey:\s*'([A-Z_]+)'/g)].map((match) => match[1])
  )
  for (const item of MENU_ITEMS) {
    assert.ok(routePaths.has(item.path), `路由缺少菜单路径: ${item.path}`)
    assert.ok(routeMenuKeys.has(item.key), `路由缺少 meta.menuKey: ${item.key}`)
  }
  assert.deepEqual([...routeMenuKeys].sort(), [...MENU_KEYS].sort())
})

test('角色允许菜单：ADMIN 全部，REVIEWER / VIEWER 仅非 adminOnly（未知角色按最小权限）', () => {
  assert.deepEqual(menuKeysForRole('ADMIN'), MENU_KEYS)
  assert.deepEqual(menuKeysForRole('REVIEWER'), NON_ADMIN_KEYS)
  assert.deepEqual(menuKeysForRole('VIEWER'), NON_ADMIN_KEYS)
  assert.deepEqual(menuKeysForRole(undefined), NON_ADMIN_KEYS)
})

test('resolveVisibleMenuKeys：绑定优先、未知 key 忽略、空绑定回退角色默认', () => {
  assert.deepEqual(resolveVisibleMenuKeys(null), [])
  assert.deepEqual(
    resolveVisibleMenuKeys({ role: 'VIEWER', menuKeys: ['DASHBOARD', 'RECORDINGS'] }),
    ['DASHBOARD', 'RECORDINGS']
  )
  // 前端尚不支持的新菜单 key 不能把可见菜单撑空，也不能污染已知 key
  assert.deepEqual(
    resolveVisibleMenuKeys({ role: 'VIEWER', menuKeys: ['FUTURE_MENU', 'REVIEW'] }),
    ['REVIEW']
  )
  // 旧后端 / 空绑定：回退角色默认，避免用户被锁进空白侧栏
  assert.deepEqual(
    resolveVisibleMenuKeys({ role: 'ADMIN', menuKeys: [] }),
    menuKeysForRole('ADMIN')
  )
  assert.deepEqual(resolveVisibleMenuKeys({ role: 'REVIEWER' }), NON_ADMIN_KEYS)
})

test('visibleMenuGroups：过滤不可见项并移除空分组', () => {
  const groups = visibleMenuGroups({ role: 'VIEWER', menuKeys: ['RECORDINGS', 'CORPUS'] })
  assert.deepEqual(
    groups.map((group) => group.items.map((item) => item.key)),
    [['RECORDINGS'], ['CORPUS']]
  )
  const all = visibleMenuGroups({ role: 'ADMIN', menuKeys: [] })
  assert.equal(all.length, MENU_GROUPS.length)
})

test('firstAllowedPath：绑定后首页不再固定为 /，且不把全屏页作为落地页', () => {
  assert.equal(firstAllowedPath({ role: 'VIEWER', menuKeys: ['DASHBOARD'] }), '/')
  assert.equal(
    firstAllowedPath({ role: 'VIEWER', menuKeys: ['RECORDINGS', 'REVIEW'] }),
    '/recordings'
  )
  assert.equal(firstAllowedPath({ role: 'ADMIN', menuKeys: ['USERS'] }), '/users')
  // 全屏页（数据大屏）不渲染壳层：有普通菜单时优先普通菜单，避免「退出大屏」被弹回自身
  assert.equal(firstAllowedPath({ role: 'ADMIN', menuKeys: ['SCREEN', 'SETTINGS'] }), '/settings')
  // 全屏页是唯一可见菜单时才回退到它
  assert.equal(firstAllowedPath({ role: 'ADMIN', menuKeys: ['SCREEN'] }), '/screen')
  // 无任何可用菜单（极端数据）回退 `/`，与后端「至少 1 个菜单」校验互为兜底
  assert.equal(firstAllowedPath({ role: 'VIEWER', menuKeys: ['FUTURE_MENU'] }), '/')
  assert.equal(firstAllowedPath(null), '/')
})

test('menuGateDecision：放行 / 落地静默跳转 / 未绑定页面提示跳转 / 自跳转中止', () => {
  const reviewer = { role: 'REVIEWER', menuKeys: ['RECORDINGS', 'REVIEW'] }
  // 无 menuKey 的公开页 / 菜单可见 → 放行
  assert.equal(menuGateDecision('/login', undefined, reviewer), null)
  assert.equal(menuGateDecision('/recordings', 'RECORDINGS', reviewer), null)
  // 登录落地页 `/` 被绑定掉：静默跳到首个可见菜单（不弹「未绑定」错误）
  assert.deepEqual(menuGateDecision('/', 'DASHBOARD', reviewer), {
    path: '/recordings',
    notify: false
  })
  // 直接访问未绑定页面：提示 + 跳首个可见菜单
  assert.deepEqual(menuGateDecision('/users', 'USERS', reviewer), {
    path: '/recordings',
    notify: true
  })
  // 极端数据：首菜单路径与目标相同但不是其 menuKey → 中止导航，避免重定向到自己
  assert.deepEqual(menuGateDecision('/recordings', 'KNOWLEDGE', reviewer), { stay: true })
})

test('reconcileMenuSelection：角色切换时全选跟随角色，否则保留交集，空交集回填', () => {
  const reviewer = menuKeysForRole('REVIEWER')
  const admin = menuKeysForRole('ADMIN')

  // 原来全选旧角色 → 切到 ADMIN 自动全选（用户预期「跟随角色」）
  assert.deepEqual(reconcileMenuSelection(reviewer, reviewer, admin), admin)
  // 受限子集 → 保留与新角色允许项的交集（REVIEWER 可用的 2 项在 ADMIN 下仍合法）
  assert.deepEqual(reconcileMenuSelection(['DASHBOARD', 'REVIEW'], reviewer, admin), [
    'DASHBOARD',
    'REVIEW'
  ])
  // 交集为空（子集全是 ADMIN 专属菜单）→ 回填新角色允许项，避免空选择死状态
  assert.deepEqual(
    reconcileMenuSelection(['COSTS', 'USERS'], ['COSTS', 'USERS'], reviewer),
    reviewer
  )
  // 没有旧角色信息（首次进入）→ 回退新角色允许项
  assert.deepEqual(reconcileMenuSelection([], [], reviewer), reviewer)
})
