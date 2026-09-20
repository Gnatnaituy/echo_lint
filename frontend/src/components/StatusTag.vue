<template>
  <el-tag :type="meta?.type || 'info'" size="small" effect="light">
    {{ meta?.label || status }}
  </el-tag>
</template>

<script setup>
import { computed } from 'vue'
import { STATUS_META } from '../constants'

const props = defineProps({
  status: { type: String, required: true }
})

const meta = computed(() => STATUS_META[props.status])
</script>