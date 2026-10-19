<template>
  <!-- 公开路由（登录页）使用独立布局，不套侧栏/顶栏壳层 -->
  <router-view v-if="isPublic" />

  <div v-else class="shell">
    <AppSidebar :collapsed="collapsed" :badge-count="pendingReview" @toggle="collapsed = !collapsed" />
    <div class="shell-main">
      <AppHeader
        :title="route.meta?.title"
        :pending="pendingReview"
        :healthy="healthy"
        @refresh="refreshStatus"
      />
      <main class="shell-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from './api'
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'

const route = useRoute()
const collapsed = ref(false)
const pendingReview = ref(0)
const healthy = ref(true)

/** 登录页等公开路由：无壳层，也不轮询业务统计 */
const isPublic = computed(() => route.meta?.public === true)

async function refreshStatus() {
  if (isPublic.value) return
  try {
    const data = await api.overview()
    pendingReview.value = data.statusCounts?.NEEDS_REVIEW || 0
    healthy.value = true
  } catch {
    healthy.value = false
  }
}

watch(
  () => route.path,
  () => {
    if (route.path === '/review' || route.path === '/') refreshStatus()
  }
)

// 窄屏自动收起侧栏
function syncCollapse() {
  if (window.innerWidth < 1180) collapsed.value = true
}

let timer = null
function startPolling() {
  if (timer) return
  refreshStatus()
  timer = setInterval(refreshStatus, 15000)
}
function stopPolling() {
  if (!timer) return
  clearInterval(timer)
  timer = null
}

// 登录/退出切换时启停轮询，避免登录页上请求受保护接口
watch(isPublic, (pub) => {
  if (pub) stopPolling()
  else startPolling()
})

onMounted(() => {
  syncCollapse()
  window.addEventListener('resize', syncCollapse)
  if (!isPublic.value) startPolling()
})
onUnmounted(() => {
  window.removeEventListener('resize', syncCollapse)
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
}
.shell-content {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px 32px;
  background:
    radial-gradient(1200px 400px at 15% -10%, #eef3ff 0%, rgba(244, 246, 251, 0) 60%),
    var(--surface-sunken);
}
</style>
