<template>
  <header class="topbar">
    <div class="left">
      <div class="crumb">
        <span class="crumb-root">稽核平台</span>
        <el-icon class="crumb-sep" :size="12"><ArrowRight /></el-icon>
        <span class="crumb-current">{{ title || '工作台' }}</span>
      </div>
    </div>

    <div class="right">
      <button class="ghost" :class="healthy ? 'is-ok' : 'is-bad'" @click="$emit('refresh')">
        <span class="dot" :style="{ background: healthy ? '#16a34a' : '#ef4444' }"></span>
        {{ healthy ? '服务正常' : '服务异常' }}
        <el-icon :size="12" class="ghost-refresh"><Refresh /></el-icon>
      </button>

      <button class="ghost" @click="router.push('/review')">
        <el-icon :size="14"><Bell /></el-icon>
        待复检
        <span class="count" :class="{ zero: !pending }">{{ pending > 99 ? '99+' : pending }}</span>
      </button>

      <div class="divider"></div>

      <div class="user">
        <div class="avatar">管</div>
        <div class="user-meta">
          <div class="user-name">管理员</div>
          <div class="user-role">Compliance Ops</div>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { useRouter } from 'vue-router'

defineProps({
  title: { type: String, default: '' },
  pending: { type: Number, default: 0 },
  healthy: { type: Boolean, default: true }
})
defineEmits(['refresh'])

const router = useRouter()
</script>

<style scoped>
.topbar {
  height: 58px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}
.crumb-root {
  color: var(--el-text-color-placeholder);
}
.crumb-sep {
  color: var(--ink-300);
}
.crumb-current {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink-700);
}

.right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
}
.ghost:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
  background: var(--brand-50);
}
.ghost.is-ok {
  color: #12823b;
  border-color: #cfe9d8;
  background: #f4fbf6;
}
.ghost.is-bad {
  color: #c92c2c;
  border-color: #f7c9c9;
  background: #fdf5f5;
}
.ghost:hover .ghost-refresh {
  transform: rotate(90deg);
}
.ghost-refresh {
  transition: transform 0.3s ease;
  opacity: 0.6;
}
.ghost .dot {
  width: 6px;
  height: 6px;
}

.count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.count.zero {
  background: var(--ink-200);
  color: var(--ink-500);
}

.divider {
  width: 1px;
  height: 22px;
  background: var(--border);
  margin: 0 2px;
}

.user {
  display: flex;
  align-items: center;
  gap: 9px;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
}
.user-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-700);
  line-height: 1.3;
}
.user-role {
  font-size: 10.5px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
</style>
