<template>
  <aside
    class="sidebar"
    :class="{ collapsed, overlay, open: overlay && open }"
    :aria-hidden="overlay && !open ? 'true' : undefined"
  >
    <!-- 品牌 -->
    <div class="brand">
      <BrandMark class="brand-mark" />
      <div v-show="showLabels" class="brand-text">
        <div class="brand-name">EchoLint</div>
        <div class="brand-sub">{{ t('app.subtitle') }}</div>
      </div>
    </div>

    <!-- 导航 -->
    <nav class="nav" :aria-label="t('a11y.toggleSidebar')">
      <template v-for="group in groups" :key="group.titleKey">
        <div v-show="showLabels" class="nav-group">{{ t(group.titleKey) }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
          :title="showLabels ? '' : t(item.labelKey)"
          :aria-label="t(item.labelKey)"
          @click="$emit('navigate')"
        >
          <el-icon class="nav-icon" :size="16"><component :is="item.icon" /></el-icon>
          <span v-show="showLabels" class="nav-label">{{ t(item.labelKey) }}</span>
          <span
            v-if="item.badge && badgeCount > 0"
            class="nav-badge"
            :class="{ dotOnly: !showLabels }"
          >
            {{ badgeCount > 99 ? '99+' : badgeCount }}
          </span>
        </router-link>
      </template>
    </nav>

    <!-- 底部折叠按钮 -->
    <div class="sidebar-foot">
      <el-tooltip :content="t('a11y.toggleSidebar')" placement="right">
        <button
          class="collapse-btn"
          type="button"
          :aria-label="t('a11y.toggleSidebar')"
          :title="t('a11y.toggleSidebar')"
          @click="$emit('toggle')"
        >
          <el-icon :size="15"><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
      </el-tooltip>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import auth from '../auth'
import {
  ChatDotRound,
  Collection,
  DataAnalysis,
  DocumentChecked,
  Expand,
  Files,
  Fold,
  Key,
  List,
  Monitor,
  Money,
  Notebook,
  Odometer,
  Operation,
  Reading,
  Setting,
  User
} from './icons.js'
import { visibleMenuGroups } from '../menu.js'
import { useI18n } from '../i18n'
import BrandMark from './BrandMark.vue'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  badgeCount: { type: Number, default: 0 },
  /** 窄屏抽屉模式：固定定位 + 遮罩，由 App.vue 控制开合 */
  overlay: { type: Boolean, default: false },
  open: { type: Boolean, default: false }
})
defineEmits(['toggle', 'navigate'])

const route = useRoute()
const { t } = useI18n()

/** 抽屉模式下始终展示完整标签（不受桌面折叠态影响） */
const showLabels = computed(() => (props.overlay ? props.open : !props.collapsed))

/** 菜单目录 key → 图标：目录本身保持纯数据（供 auth/路由/测试复用），图标只在渲染层绑定 */
const MENU_ICONS = {
  DASHBOARD: Odometer,
  RECORDINGS: Files,
  REPORTS: DataAnalysis,
  REVIEW: DocumentChecked,
  SCREEN: Monitor,
  DICTIONARY: Collection,
  CORPUS: Notebook,
  KNOWLEDGE: Reading,
  SETTINGS: Setting,
  COSTS: Money,
  TASKS: Operation,
  AI_CHAT: ChatDotRound,
  API_KEYS: Key,
  USERS: User,
  AUDIT: List
}

// 可见菜单 = 角色允许范围 ∩ 用户菜单绑定（由后端 /api/auth/me 下发）；
// 菜单绑定只影响前端入口，接口权限仍由后端角色矩阵独立校验。
const groups = computed(() =>
  visibleMenuGroups(auth.user).map((group) => ({
    ...group,
    items: group.items.map((item) => ({ ...item, icon: MENU_ICONS[item.key] || List }))
  }))
)

// 前缀匹配要求路径段边界：/knowledge 不能把 /knowledge-xxx 误判为激活
const isActive = (path) =>
  path === '/' ? route.path === '/' : route.path === path || route.path.startsWith(`${path}/`)

const badgeCount = computed(() => props.badgeCount)
</script>

<style scoped>
.sidebar {
  width: 236px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--surface-sidebar);
  color: var(--ink-600);
  border-right: 1px solid var(--border);
  box-shadow: 1px 0 0 rgba(16, 26, 48, 0.02);
  transition: width 0.22s cubic-bezier(0.22, 1, 0.36, 1);
  position: relative;
  z-index: 2;
}
.sidebar.collapsed {
  width: 68px;
}

/* 窄屏抽屉模式：固定定位 + 滑入/滑出；关闭时 visibility hidden 防止 Tab 聚焦到屏幕外元素 */
.sidebar.overlay {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 248px;
  z-index: 70;
  transform: translateX(-100%);
  visibility: hidden;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.18);
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    visibility 0.22s;
}
.sidebar.overlay.open {
  transform: translateX(0);
  visibility: visible;
}
.sidebar.overlay.collapsed {
  width: 248px;
}

/* 折叠态：图标在胶囊/品牌格里居中（否则 padding 会把图标顶偏） */
.sidebar.collapsed .brand {
  justify-content: center;
  padding: 0;
}
.sidebar.collapsed .nav-item {
  justify-content: center;
  padding: 0;
  gap: 0;
}

/* ---------- 品牌区：浅色卡片式分区 ---------- */
.brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 58px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(180deg, #fff 0%, rgba(251, 252, 255, 0.82) 100%);
}
.brand::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: -1px;
  height: 1px;
  background: linear-gradient(90deg, rgba(59, 110, 246, 0.28), rgba(59, 110, 246, 0));
  pointer-events: none;
}
.brand-mark {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 10px;
  box-shadow: 0 4px 10px rgba(59, 110, 246, 0.24);
}
.brand-text {
  min-width: 0;
}
.brand-name {
  color: var(--ink-900);
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.01em;
  white-space: nowrap;
}
.brand-sub {
  font-size: 10px;
  color: var(--ink-500);
  letter-spacing: 0.11em;
  text-transform: uppercase;
  white-space: nowrap;
}

/* ---------- 导航 ---------- */
.nav {
  flex: 1;
  padding: 10px 12px 8px;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: #d7dfec transparent;
}
.nav::-webkit-scrollbar {
  width: 6px;
}
.nav::-webkit-scrollbar-thumb {
  background: #d7dfec;
  border-radius: 3px;
}
.nav::-webkit-scrollbar-thumb:hover {
  background: #c1cddf;
}
.nav::-webkit-scrollbar-track {
  background: transparent;
}
.nav-group {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 10px 7px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  color: var(--ink-500);
  text-transform: uppercase;
  white-space: nowrap;
}
.nav-group::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--ink-200), rgba(225, 231, 240, 0));
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  margin: 2px 0;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--ink-600);
  font-size: 13.5px;
  font-weight: 500;
  transition:
    background 0.16s ease,
    color 0.16s ease,
    border-color 0.16s ease,
    box-shadow 0.16s ease;
  white-space: nowrap;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.72);
  border-color: var(--border);
  color: var(--ink-800);
}
.nav-icon {
  flex: none;
  color: var(--ink-400);
  transition: color 0.16s ease;
}
.nav-item:hover .nav-icon {
  color: var(--ink-600);
}
/* 选中项：实心深品牌底（而非浅蓝渐变）——整页唯一的深色块，
   视线能立刻定位当前页；白字在 brand-600/700 上实测 6.3–8.5:1，满足 WCAG AA */
.nav-item.active {
  background: linear-gradient(180deg, var(--brand-600) 0%, var(--brand-700) 100%);
  border-color: var(--brand-700);
  color: #fff;
  font-weight: 600;
  box-shadow:
    0 1px 2px rgba(38, 71, 158, 0.28),
    0 4px 12px rgba(47, 88, 197, 0.24);
}
.nav-item.active .nav-icon {
  color: rgba(255, 255, 255, 0.92);
}
.nav-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nav-badge {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(239, 68, 68, 0.32);
}
/* 选中项上的角标：红底贴着蓝底会发浑，改成白色描边式 */
.nav-item.active .nav-badge {
  background: #fff;
  color: #c92c2c;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.18);
}
.nav-badge.dotOnly {
  position: absolute;
  top: 6px;
  right: 8px;
  min-width: 8px;
  height: 8px;
  padding: 0;
  font-size: 0;
}

/* ---------- 底部折叠按钮 ---------- */
.sidebar-foot {
  padding: 12px 14px 14px;
  border-top: 1px solid var(--border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.55), rgba(238, 242, 249, 0.85));
}
.collapse-btn {
  width: 100%;
  height: 32px;
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  background: #fff;
  color: var(--ink-500);
  cursor: pointer;
  display: grid;
  place-items: center;
  box-shadow: var(--sh-xs);
  transition:
    background 0.16s ease,
    color 0.16s ease,
    border-color 0.16s ease;
}
.collapse-btn:hover {
  background: var(--brand-50);
  border-color: var(--brand-200);
  color: var(--brand-600);
}
</style>
