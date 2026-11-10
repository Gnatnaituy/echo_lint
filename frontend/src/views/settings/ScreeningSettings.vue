<template>
  <!--
    复筛模型页：一张主卡片（当前生效 + 供应商选择 + 模型切换）+ A/B 与评测。
    供应商注册表只在 ADMIN 进入本页时加载；普通用户只读状态行。
  -->
  <div class="stack">
    <ModelSwitchCard />
    <AbEvalCard v-if="canWrite" />
  </div>
</template>

<script setup>
import { defineAsyncComponent, onMounted } from 'vue'
import { useSettingsContext } from './context'
import ModelSwitchCard from './ModelSwitchCard.vue'

const { canWrite, model, registry } = useSettingsContext()

// A/B 与评测仅 ADMIN：异步加载，普通用户不下载对应 chunk
const AbEvalCard = defineAsyncComponent(() => import('./AbEvalCard.vue'))

onMounted(() => {
  // 状态行所有登录用户都要显示；供应商注册表仅 ADMIN 需要（非 ADMIN 不请求）
  model.load()
  if (canWrite.value) registry.load()
})
</script>
