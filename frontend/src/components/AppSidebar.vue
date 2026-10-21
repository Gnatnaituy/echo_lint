<template>
  <aside
    class="sidebar"
    :class="{ collapsed, overlay, open: overlay && open }"
    :aria-hidden="overlay && !open ? 'true' : undefined"
  >
    <!-- 品牌 -->
    <div class="brand">
      <div class="brand-mark">
        <el-icon :size="18"><Headset /></el-icon>
      </div>
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
          <span v-if="item.badge && badgeCount > 0" class="nav-badge" :class="{ dotOnly: !showLabels }">
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
          <el-icon :size="15"><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        </button>
      </el-tooltip>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import auth from '../auth'
import { useI18n } from '../i18n'

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

// 系统设置 / 知识库 / 数据大屏对所有角色可见；成本看板 / 任务队列 / API 密钥 / 用户管理 / 操作审计仅 ADMIN
const groups = computed(() => {
  const systemItems = [{ path: '/settings', labelKey: 'nav.settings', icon: 'Setting' }]
  if (auth.hasRole('ADMIN')) {
    systemItems.push(
      { path: '/costs', labelKey: 'nav.costs', icon: 'Money' },
      { path: '/tasks', labelKey: 'nav.tasks', icon: 'Operation' },
      { path: '/ai-chat', labelKey: 'nav.aiChat', icon: 'ChatDotRound' },
      { path: '/api-keys', labelKey: 'nav.apiKeys', icon: 'Key' },
      { path: '/users', labelKey: 'nav.users', icon: 'User' },
      { path: '/audit', labelKey: 'nav.audit', icon: 'List' }
    )
  }
  return [
    {
      titleKey: 'nav.group.auditOps',
      items: [
        { path: '/', labelKey: 'nav.dashboard', icon: 'Odometer' },
        { path: '/recordings', labelKey: 'nav.recordings', icon: 'Files' },
        { path: '/reports', labelKey: 'nav.reports', icon: 'DataAnalysis' },
        { path: '/review', labelKey: 'nav.review', icon: 'DocumentChecked', badge: true },
        { path: '/screen', labelKey: 'nav.screen', icon: 'Monitor' }
      ]
    },
    {
      titleKey: 'nav.group.knowledge',
      items: [
        { path: '/dictionary', labelKey: 'nav.dictionary', icon: 'Collection' },
        { path: '/corpus', labelKey: 'nav.corpus', icon: 'Notebook' },
        { path: '/knowledge', labelKey: 'nav.knowledge', icon: 'Reading' }
      ]
    },
    { titleKey: 'nav.group.system', items: systemItems }
  ]
})

const isActive = (path) => (path === '/' ? route.path === '/' : route.path.startsWith(path))

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
  box-shadow: 8px 0 24px rgba(15, 23, 42, 0.035);
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
  transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1), visibility 0.22s;
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
  height: 60px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 250, 255, 0.72));
}
.brand::after {
  content: "";
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: -1px;
  height: 1px;
  background: linear-gradient(90deg, rgba(59, 110, 246, 0.35), rgba(59, 110, 246, 0));
  pointer-events: none;
}
.brand-mark {
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 11px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 6px 14px rgba(59, 110, 246, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}
.brand-text {
  min-width: 0;
}
.brand-name {
  color: var(--ink-900);
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.02em;
  white-space: nowrap;
}
.brand-sub {
  font-size: 10.5px;
  color: #8493ab;
  letter-spacing: 0.1em;
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
  padding: 15px 10px 7px;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.13em;
  color: #8b99af;
  text-transform: uppercase;
  white-space: nowrap;
}
.nav-group::after {
  content: "";
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, #e2e9f4, rgba(226, 233, 244, 0));
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  margin: 2px 0;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  color: #4a5872;
  font-size: 13.5px;
  font-weight: 500;
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease,
    box-shadow 0.16s ease;
  white-space: nowrap;
}
.nav-item:hover {
  background: #eef3ff;
  border-color: #e3ebff;
  color: var(--brand-700);
}
.nav-icon {
  flex: none;
  color: #8592a8;
  transition: color 0.16s ease;
}
.nav-item:hover .nav-icon {
  color: var(--brand-500);
}
.nav-item.active {
  background: linear-gradient(100deg, #e9f0ff 0%, #f5f8ff 100%);
  border-color: #d8e4ff;
  color: var(--brand-700);
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(59, 110, 246, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.nav-item.active .nav-icon {
  color: var(--brand-600);
}
.nav-item.active::before {
  content: "";
  position: absolute;
  left: -12px;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 0 8px rgba(59, 110, 246, 0.35);
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
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.45), rgba(239, 244, 252, 0.9));
}
.collapse-btn {
  width: 100%;
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: #fff;
  color: #6b7a91;
  cursor: pointer;
  display: grid;
  place-items: center;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}
.collapse-btn:hover {
  background: var(--brand-50);
  border-color: #cddcff;
  color: var(--brand-600);
}
</style>
