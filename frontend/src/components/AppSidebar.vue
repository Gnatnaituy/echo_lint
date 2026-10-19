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

    <!-- 底部流程提示 -->
    <div class="sidebar-foot">
      <div v-show="showLabels" class="flow">
        <div class="flow-title">{{ t('side.flowTitle') }}</div>
        <ol class="flow-steps">
          <li>{{ t('side.flowTranscribe') }}</li>
          <li>{{ t('side.flowDfa') }}</li>
          <li>{{ t('side.flowAi') }}</li>
          <li>{{ t('side.flowReview') }}</li>
          <li>{{ t('side.flowFeedback') }}</li>
        </ol>
      </div>
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
  width: 232px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--surface-sidebar);
  color: rgba(255, 255, 255, 0.72);
  transition: width 0.22s cubic-bezier(0.22, 1, 0.36, 1);
  position: relative;
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
  box-shadow: 0 18px 46px rgba(2, 6, 23, 0.38);
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

/* 品牌区 */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 60px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}
.brand-mark {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 4px 12px rgba(59, 110, 246, 0.4);
}
.brand-text {
  min-width: 0;
}
.brand-name {
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}
.brand-sub {
  font-size: 10.5px;
  color: rgba(255, 255, 255, 0.42);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  white-space: nowrap;
}

/* 导航 */
.nav {
  flex: 1;
  padding: 12px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}
.nav::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.16);
}
.nav-group {
  padding: 14px 10px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.34);
  white-space: nowrap;
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 11px;
  border-radius: 9px;
  color: rgba(255, 255, 255, 0.74);
  font-size: 13.5px;
  transition: background 0.16s ease, color 0.16s ease;
  white-space: nowrap;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
}
.nav-item.active {
  background: linear-gradient(90deg, rgba(59, 110, 246, 0.9), rgba(59, 110, 246, 0.62));
  color: #fff;
  box-shadow: 0 6px 16px rgba(59, 110, 246, 0.32);
}
.nav-item.active::before {
  content: "";
  position: absolute;
  left: -10px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #fff;
}
.nav-icon {
  flex: none;
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

/* 底部 */
.sidebar-foot {
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}
.flow {
  margin-bottom: 10px;
}
.flow-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.34);
  margin-bottom: 8px;
}
.flow-steps {
  margin: 0;
  padding-left: 16px;
  font-size: 11.5px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.5);
}
.collapse-btn {
  width: 100%;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.07);
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.16s ease, color 0.16s ease;
}
.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}
</style>
