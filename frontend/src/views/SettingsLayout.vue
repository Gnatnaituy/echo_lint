<template>
  <!--
    系统设置容器：页头（主标题 + 一句说明）→ 二级导航 → 子页面。
    共享状态（当前生效模型 / 供应商注册表）由容器创建后 provide 下发；数据请求由各子页面
    在自己的 onMounted 里按需触发（复筛页才请求模型与供应商，ADMIN 页面异步加载）。
  -->
  <div class="page stack settings-layout">
    <header class="settings-head">
      <h1 class="settings-title">{{ t(route.meta?.titleKey || 'nav.settings') }}</h1>
      <p v-if="description" class="settings-desc">{{ description }}</p>
    </header>

    <!--
      二级导航沿用 Knowledge.vue 的胶囊轨道视觉语言（view-tabs / view-tab）。
      与页内 Tab 不同，这里是真实路由跳转，因此用 <nav> + router-link + aria-current="page"：
      原生链接键盘可达（Tab / Enter），无需再模拟 roving tabindex。
    -->
    <nav class="view-tabs" :aria-label="t('settings.nav.label')">
      <router-link
        v-for="tab in tabs"
        :key="tab.path"
        :to="tab.path"
        class="view-tab"
        :class="{ on: isActive(tab.path) }"
        :aria-current="isActive(tab.path) ? 'page' : undefined"
      >
        <el-icon :size="15"><component :is="tab.icon" /></el-icon>
        <span>{{ t(tab.labelKey) }}</span>
      </router-link>
    </nav>

    <router-view />
  </div>
</template>

<script setup>
import { computed, provide } from 'vue'
import { useRoute } from 'vue-router'
import auth from '../auth'
import { useI18n } from '../i18n'
import { Bell, Cpu, Microphone, Monitor, Reading } from '../components/icons.js'
import { SETTINGS_CONTEXT } from './settings/context.js'
import { useProviderRegistry } from './settings/useProviderRegistry.js'
import { useScreenModel } from './settings/useScreenModel.js'

const { t } = useI18n()
const route = useRoute()

/** 系统设置写操作仅 ADMIN：非管理员只读查看，后端仍会独立校验 */
const canWrite = computed(() => auth.hasRole('ADMIN'))

// 共享状态由容器创建后 provide 下发：各子页面 inject 后共用 view / draft / testResult 与供应商注册表
const model = useScreenModel()
const registry = useProviderRegistry(canWrite)
provide(SETTINGS_CONTEXT, { canWrite, model, registry })

/**
 * 二级导航：ADMIN 专用项对普通用户隐藏（与子路由 meta.roles、后端角色矩阵三重校验）。
 * 图标来自 components/icons.js 的按需引入表，避免把全部图标打进首屏。
 */
const ALL_TABS = [
  { path: '/settings/screening', labelKey: 'settings.nav.screening', icon: Cpu },
  {
    path: '/settings/transcription',
    labelKey: 'settings.nav.transcription',
    icon: Microphone,
    adminOnly: true
  },
  { path: '/settings/rag', labelKey: 'settings.nav.rag', icon: Reading, adminOnly: true },
  {
    path: '/settings/notifications',
    labelKey: 'settings.nav.notifications',
    icon: Bell,
    adminOnly: true
  },
  { path: '/settings/runtime', labelKey: 'settings.nav.runtime', icon: Monitor }
]

const tabs = computed(() => ALL_TABS.filter((tab) => !tab.adminOnly || canWrite.value))

/** 前缀匹配 + 路径段边界：/settings/rag 高亮「知识增强」，不会误伤 /settings-xxx */
const isActive = (path) => route.path === path || route.path.startsWith(`${path}/`)

/** 页头说明按子路由 meta.descKey 翻译（缺失时不渲染说明行） */
const description = computed(() => {
  const key = route.meta?.descKey
  return key ? t(key) : ''
})
</script>

<style scoped>
.settings-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.settings-title {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.3;
  color: var(--ink-800);
  letter-spacing: 0.2px;
}
.settings-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--ink-500);
}

/* 二级导航：灰底胶囊轨道 + 选中白色胶囊（与 Knowledge.vue 的 view-tabs 同一套视觉语言） */
.view-tabs {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 2px;
  max-width: 100%;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--ink-100);
  flex-wrap: wrap;
}
.view-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 13px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  text-decoration: none;
  cursor: pointer;
  transition:
    background 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease;
}
.view-tab:hover:not(.on) {
  color: var(--brand-600);
}
.view-tab.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.view-tab:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}
@media (max-width: 640px) {
  .view-tabs {
    width: 100%;
  }
  .view-tab {
    flex: 1;
    justify-content: center;
  }
}
</style>
