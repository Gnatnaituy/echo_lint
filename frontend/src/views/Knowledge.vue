<template>
  <div class="page stack">
    <!-- 页内视图切换：索引明细并入知识库页，仅 ADMIN 可见（后端分块接口同样只放开 ADMIN） -->
    <div class="view-tabs" role="tablist" :aria-label="t('knowledge.tabs.label')">
      <button
        id="knowledge-tab-entries"
        ref="entriesTab"
        type="button"
        role="tab"
        class="view-tab"
        :class="{ on: activeTab === 'entries' }"
        :aria-selected="activeTab === 'entries' ? 'true' : 'false'"
        aria-controls="knowledge-entries-panel"
        :tabindex="activeTab === 'entries' ? 0 : -1"
        @click="switchTab('entries')"
        @keydown="onTabKeydown($event, 'entries')"
      >
        <el-icon :size="15"><Reading /></el-icon>
        <span>{{ t('knowledge.pageTitle') }}</span>
      </button>
      <button
        v-if="canViewIndex"
        id="knowledge-tab-index"
        ref="indexTab"
        type="button"
        role="tab"
        class="view-tab"
        :class="{ on: activeTab === 'index' }"
        :aria-selected="activeTab === 'index' ? 'true' : 'false'"
        aria-controls="knowledge-index-panel"
        :tabindex="activeTab === 'index' ? 0 : -1"
        @click="switchTab('index')"
        @keydown="onTabKeydown($event, 'index')"
      >
        <el-icon :size="15"><Tickets /></el-icon>
        <span>{{ t('knowledgeIndex.pageTitle') }}</span>
      </button>
    </div>

    <KnowledgeEntries
      v-if="activeTab === 'entries'"
      id="knowledge-entries-panel"
      role="tabpanel"
      aria-labelledby="knowledge-tab-entries"
    />
    <KnowledgeIndex
      v-else-if="activeTab === 'index'"
      id="knowledge-index-panel"
      role="tabpanel"
      aria-labelledby="knowledge-tab-index"
    />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Reading, Tickets } from '@element-plus/icons-vue'
import auth from '../auth'
import KnowledgeEntries from './KnowledgeEntries.vue'
import { useI18n } from '../i18n'

// 索引明细面板按需加载：非管理员不需要下载这部分代码
const KnowledgeIndex = defineAsyncComponent(() => import('./KnowledgeIndex.vue'))

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const entriesTab = ref(null)
const indexTab = ref(null)

/** 与后端 /api/knowledge/{id}/chunks 一致：索引明细仅管理员可见 */
const canViewIndex = computed(() => auth.hasRole('ADMIN'))

/** 视图以 URL query 为准：/knowledge?tab=index 可深链（旧 /knowledge-index 会重定向到这里） */
const activeTab = computed(() =>
  canViewIndex.value && route.query.tab === 'index' ? 'index' : 'entries'
)

/** 切换视图并同步 URL：保留其它 query，只增删 tab 参数 */
async function switchTab(tab) {
  if (tab === activeTab.value) return
  const query = { ...route.query }
  if (tab === 'index') query.tab = 'index'
  else delete query.tab
  await router.replace({ query })
}

// 非管理员通过旧书签进入 /knowledge?tab=index 时，清掉无效的 tab 参数
onMounted(() => {
  if (route.query.tab === 'index' && !canViewIndex.value) {
    const query = { ...route.query }
    delete query.tab
    router.replace({ query })
  }
})

/** 键盘操作：← / → 在两个视图间切换并移动焦点（tabs 的 roving tabindex） */
async function onTabKeydown(event, tab) {
  if (!canViewIndex.value) return
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const next = tab === 'entries' ? 'index' : 'entries'
  await switchTab(next)
  await nextTick()
  const target = next === 'entries' ? entriesTab.value : indexTab.value
  target?.focus?.()
}
</script>

<style scoped>
/* 页内 Tab：灰底胶囊轨道 + 选中白色胶囊，与页面内 SegmentedControl 同一套视觉语言 */
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
  border-radius: 10px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
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
