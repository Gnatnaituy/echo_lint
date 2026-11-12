<template>
  <el-tag v-if="meta" :type="meta.type" size="small" effect="light" class="status-tag">
    <span class="dot" :style="{ background: dotColor }"></span>
    {{ meta.label }}
  </el-tag>
  <el-tag v-else size="small" type="info">{{ status }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'
import { STATUS_META } from '../constants'

const props = defineProps({
  status: { type: String, required: true }
})

const meta = computed(() => STATUS_META[props.status])

const DOT_COLORS = {
  info: '#94a3b8',
  warning: '#f59e0b',
  danger: '#ef4444',
  success: '#16a34a'
}
const dotColor = computed(() => DOT_COLORS[meta.value?.type] || '#94a3b8')
</script>

<style scoped>
.status-tag {
  gap: 5px;
  /* 浅色 tag 的底色在白卡上边界发虚，补一圈极淡同色描边定形 */
  box-shadow: inset 0 0 0 1px rgba(100, 116, 139, 0.1);
}
.status-tag .dot {
  width: 5px;
  height: 5px;
  box-shadow: 0 0 0 2px rgba(148, 163, 184, 0.14);
}
</style>
