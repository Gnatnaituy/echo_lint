<template>
  <div class="shell">
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
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from './api'
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'

const route = useRoute()
const collapsed = ref(false)
const pendingReview = ref(0)
const healthy = ref(true)

async function refreshStatus() {
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
onMounted(() => {
  syncCollapse()
  window.addEventListener('resize', syncCollapse)
  refreshStatus()
  timer = setInterval(refreshStatus, 15000)
})
onUnmounted(() => {
  window.removeEventListener('resize', syncCollapse)
  clearInterval(timer)
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
