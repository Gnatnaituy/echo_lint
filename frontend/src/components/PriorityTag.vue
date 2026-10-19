<template>
  <span class="chip prio" :class="chipClass">
    <span class="prio-dot"></span>
    {{ meta.label }}
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { priorityMeta } from '../constants'

const props = defineProps({
  priority: { type: String, default: 'NORMAL' }
})

const meta = computed(() => priorityMeta(props.priority))

/** Element Plus tag 语义色 → 站点 chip 配色 */
const CHIP_BY_TYPE = {
  danger: 'danger',
  warning: 'warn',
  primary: 'brand',
  info: 'info',
  success: 'ok'
}
const chipClass = computed(() => CHIP_BY_TYPE[meta.value.type] || 'info')
</script>

<style scoped>
.prio {
  gap: 5px;
}
.prio-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
</style>
