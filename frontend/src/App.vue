<template>
  <!-- Element Plus 组件（分页 / 日期选择 / 空态等）语言跟随站点语言 -->
  <el-config-provider :locale="elementLocale">
    <!-- 公开路由（登录页）使用独立布局，不套侧栏/顶栏壳层 -->
    <router-view v-if="isPublic" />

    <!-- 全屏路由（数据大屏）：同样不渲染壳层 -->
    <router-view v-else-if="isFullscreen" />

    <div v-else class="shell">
      <a class="skip-link" href="#main-content" @click.prevent="focusMain">
        {{ t('a11y.skipToContent') }}
      </a>

      <!-- 窄屏抽屉遮罩：点击关闭 -->
      <div
        v-if="isNarrow && drawerOpen"
        class="sidebar-backdrop"
        aria-hidden="true"
        @click="closeDrawer"
      ></div>

      <AppSidebar
        ref="sidebarRef"
        :collapsed="collapsed"
        :badge-count="pendingReview"
        :overlay="isNarrow"
        :open="drawerOpen"
        @toggle="onToggleSidebar"
        @navigate="closeDrawer"
      />
      <div class="shell-main">
        <AppHeader
          ref="headerRef"
          :title="routeTitle"
          :pending="pendingReview"
          :healthy="healthy"
          @refresh="refreshStatus"
          @toggle-menu="toggleDrawer"
        />
        <main id="main-content" ref="mainRef" class="shell-content" tabindex="-1">
          <router-view />
        </main>
      </div>
    </div>
  </el-config-provider>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import elementEn from 'element-plus/es/locale/lang/en'
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'
import { applyDocumentLang, t, useI18n } from './i18n'
import overviewState from './state/overview'
import { isEscapeKey, isNarrowWidth } from './utils/ui'

const route = useRoute()
const { locale } = useI18n()

/** Element Plus 内置文案语言包：随站点语言切换（分页、日期、空态等） */
const elementLocale = computed(() => (locale.value === 'zh' ? zhCn : elementEn))

const collapsed = ref(false)
const drawerOpen = ref(false)
const isNarrow = ref(typeof window !== 'undefined' ? isNarrowWidth(window.innerWidth) : false)
const pendingReview = ref(0)
const healthy = ref(true)

const sidebarRef = ref(null)
const headerRef = ref(null)
const mainRef = ref(null)

/** 登录页等公开路由：无壳层，也不轮询业务统计 */
const isPublic = computed(() => route.meta?.public === true)
/** 数据大屏等全屏路由：不渲染侧栏/页头 */
const isFullscreen = computed(() => route.meta?.fullscreen === true)
/** 路由标题（跟随语言） */
const routeTitle = computed(() => {
  const meta = route.meta || {}
  if (meta.titleKey) return t(meta.titleKey)
  return meta.title || ''
})

/**
 * 刷新顶栏状态（待复检角标 + 后端健康态）。
 * 走 overview 单例：与工作台 / 语料库 / 敏感词库共享 in-flight 请求与短缓存，
 * 路由切换时不会重复打后端（force 保证轮询每次都取最新值）。
 */
async function refreshStatus() {
  if (isPublic.value || isFullscreen.value) return
  try {
    const data = await overviewState.load({ force: true })
    pendingReview.value = data?.statusCounts?.NEEDS_REVIEW || 0
    healthy.value = true
  } catch {
    healthy.value = false
  }
}

watch(
  () => route.path,
  () => {
    if (route.path === '/review' || route.path === '/') refreshStatus()
    // 导航后自动收起窄屏抽屉
    if (drawerOpen.value && isNarrow.value) closeDrawer()
  }
)

/* ---------- 窄屏抽屉：断点 / 焦点 / ESC ---------- */

/** 小于该宽度时自动折叠侧栏：给列表/表格让出 168px 横向空间 */
const AUTO_COLLAPSE_BREAKPOINT = 1280
/** 是否由「自动折叠」造成的折叠态：用户手动切换后不再被自动逻辑覆盖 */
const autoCollapsed = ref(false)

function syncLayout() {
  const narrow = isNarrowWidth(window.innerWidth)
  isNarrow.value = narrow
  if (narrow) return
  drawerOpen.value = false
  const shouldCollapse = window.innerWidth < AUTO_COLLAPSE_BREAKPOINT
  if (shouldCollapse && !collapsed.value) {
    collapsed.value = true
    autoCollapsed.value = true
  } else if (!shouldCollapse && autoCollapsed.value) {
    // 回到宽屏后恢复用户原本的展开态
    collapsed.value = false
    autoCollapsed.value = false
  }
}

/** 手动折叠/展开：以用户选择为准，不再自动覆盖 */
function onToggleSidebar() {
  collapsed.value = !collapsed.value
  autoCollapsed.value = false
}

async function openDrawer() {
  if (!isNarrow.value) return
  drawerOpen.value = true
  await nextTick()
  // 打开后把焦点移入侧栏第一个导航项，键盘用户可直接继续操作
  sidebarRef.value?.$el?.querySelector?.('.nav-item')?.focus?.()
}

function closeDrawer() {
  if (!drawerOpen.value) return
  drawerOpen.value = false
  headerRef.value?.focusMenuButton?.()
}

function toggleDrawer() {
  if (drawerOpen.value) closeDrawer()
  else openDrawer()
}

async function focusMain() {
  await nextTick()
  mainRef.value?.focus?.()
}

function onKeydown(event) {
  if (isNarrow.value && drawerOpen.value && isEscapeKey(event)) {
    event.preventDefault()
    closeDrawer()
  }
}

/* ---------- 轮询 ---------- */

const POLL_INTERVAL_MS = 15000

let timer = null

/** 轮询 tick：页面不可见时跳过请求（后台标签页不占带宽也不打扰后端） */
function tick() {
  if (typeof document !== 'undefined' && document.hidden) return
  refreshStatus()
}

/** 从后台切回前台：立即补一次，避免等到下一个 15s 才恢复角标/健康态 */
function onVisibilityChange() {
  if (typeof document === 'undefined' || document.hidden) return
  refreshStatus()
}

function startPolling() {
  if (timer) return
  refreshStatus()
  timer = setInterval(tick, POLL_INTERVAL_MS)
}
function stopPolling() {
  if (!timer) return
  clearInterval(timer)
  timer = null
}

// 登录/退出/全屏切换时启停轮询
watch([isPublic, isFullscreen], ([pub, fullscreen]) => {
  if (pub || fullscreen) stopPolling()
  else startPolling()
})

// 语言切换：同步 <html lang> 与文档标题
watch(locale, (value) => {
  applyDocumentLang(value)
  const title = routeTitle.value
  document.title = title ? `${title} · ${t('app.name')}` : `${t('app.name')} · ${t('app.subtitle')}`
})

onMounted(() => {
  syncLayout()
  applyDocumentLang(locale.value)
  window.addEventListener('resize', syncLayout)
  window.addEventListener('keydown', onKeydown)
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (!isPublic.value && !isFullscreen.value) startPolling()
})
onUnmounted(() => {
  window.removeEventListener('resize', syncLayout)
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopPolling()
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.shell-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface-sunken);
}
/* 内容区：把「画布」从白色改成浅灰蓝，白卡才有对比可浮起来。
   顶部保留一层极淡的品牌色光晕，避免大面积纯灰显得死板；
   内高光仍在，但只作用于 1px 边缘，不再把整块背景漂白。 */
.shell-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin: 14px 18px 18px 16px;
  padding: 22px 24px 34px;
  border: 1px solid rgba(220, 227, 238, 0.9);
  border-radius: var(--r-xl);
  background:
    radial-gradient(
      1100px 340px at 6% -18%,
      rgba(59, 110, 246, 0.06) 0%,
      rgba(59, 110, 246, 0) 62%
    ),
    linear-gradient(180deg, #f6f8fc 0%, #f1f4f9 100%);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 1px 2px rgba(16, 26, 48, 0.03);
  outline: none;
}

/* 跳转到主内容：默认视觉隐藏，键盘聚焦后显示 */
.skip-link {
  position: fixed;
  top: 8px;
  left: 8px;
  z-index: 200;
  padding: 8px 14px;
  border-radius: 8px;
  background: #fff;
  color: var(--brand-700, var(--brand-600));
  font-size: 13px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
  transform: translateY(-200%);
  transition: transform 0.16s ease;
}
.skip-link:focus {
  transform: translateY(0);
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

/* 窄屏遮罩 */
.sidebar-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(2, 6, 23, 0.46);
  backdrop-filter: blur(1px);
}

@media (max-width: 900px) {
  .shell-content {
    margin: 10px 10px 12px;
    padding: 14px 12px 24px;
    border-radius: 14px;
  }
}
</style>
