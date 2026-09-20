<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">
        <el-icon size="22"><Headset /></el-icon>
        <span>录音稽核</span>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/">
          <el-icon><Odometer /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/recordings">
          <el-icon><Files /></el-icon>
          <span>录音管理</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <el-icon><Checked /></el-icon>
          <span>人工复检</span>
          <el-badge v-if="pendingReview > 0" :value="pendingReview" class="menu-badge" />
        </el-menu-item>
        <el-menu-item index="/dictionary">
          <el-icon><Collection /></el-icon>
          <span>敏感词库</span>
        </el-menu-item>
        <el-menu-item index="/corpus">
          <el-icon><Notebook /></el-icon>
          <span>语料库</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-title">{{ route.meta?.title || '' }}</div>
        <div class="header-right">
          <span class="flow-hint">上传 → Whisper 转写 → DFA 初筛 → AI 复筛 → 人工复检 → 语料回馈</span>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from './api'

const route = useRoute()
const activeMenu = computed(() => route.path)
const pendingReview = ref(0)

let timer = null
async function refreshBadge() {
  try {
    const data = await api.overview()
    pendingReview.value = data.statusCounts?.NEEDS_REVIEW || 0
  } catch {
    // 后端未就绪时静默
  }
}

watch(
  () => route.path,
  () => {
    if (route.path === '/review' || route.path === '/') refreshBadge()
  }
)

onMounted(() => {
  refreshBadge()
  timer = setInterval(refreshBadge, 15000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.layout {
  height: 100vh;
}
.aside {
  background: #001529;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
}
.menu {
  border-right: none;
  background: transparent;
  flex: 1;
}
.menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.72);
}
.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: #409eff;
  color: #fff;
}
.menu-badge {
  margin-left: auto;
}
.header {
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.flow-hint {
  color: #909399;
  font-size: 12px;
}
.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>