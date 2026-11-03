<template>
  <div class="page stack">
    <!-- ① 当前生效：展示当前模型 + 连通性测试结果（② 的测试结果也显示在此） -->
    <ActiveModelCard />

    <!-- ② 切换：供应商网格 + 模型下拉 + 保存 / 测试 -->
    <ModelSwitchCard />

    <!-- ③ AI 供应商注册表（ADMIN，异步） -->
    <ProviderRegistryCard v-if="canWrite" />

    <!-- ④ A/B 与模型评测（ADMIN，异步） -->
    <AbEvalCard v-if="canWrite" />

    <!-- ⑤ RAG 检索增强（ADMIN，异步） -->
    <RagCard v-if="canWrite" />

    <!-- ⑥ 生效范围：静态说明 + 环境变量示例 -->
    <ScopeCard />

    <!-- ⑦ 抽样与通知（ADMIN，异步） -->
    <NotificationCard v-if="canWrite" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, provide } from 'vue'
import auth from '../auth'
import ActiveModelCard from './settings/ActiveModelCard.vue'
import ModelSwitchCard from './settings/ModelSwitchCard.vue'
import ScopeCard from './settings/ScopeCard.vue'
import { SETTINGS_CONTEXT } from './settings/context.js'
import { useProviderRegistry } from './settings/useProviderRegistry.js'
import { useScreenModel } from './settings/useScreenModel.js'

// 系统设置写操作仅 ADMIN：非管理员只读查看，后端仍会独立校验
const canWrite = computed(() => auth.hasRole('ADMIN'))

// 共享状态由容器创建后 provide 下发：① / ② 共用 view / draft / testResult，② / ③ / ④ 共用供应商注册表
const model = useScreenModel()
const registry = useProviderRegistry(canWrite, model.view)

provide(SETTINGS_CONTEXT, { canWrite, model, registry })

// ADMIN 面板按需异步加载：非管理员不渲染即不下载对应 chunk；四个面板各自挂载时再请求 ADMIN 接口
const ProviderRegistryCard = defineAsyncComponent(
  () => import('./settings/ProviderRegistryCard.vue')
)
const AbEvalCard = defineAsyncComponent(() => import('./settings/AbEvalCard.vue'))
const RagCard = defineAsyncComponent(() => import('./settings/RagCard.vue'))
const NotificationCard = defineAsyncComponent(() => import('./settings/NotificationCard.vue'))

onMounted(() => {
  // 当前生效模型：所有登录用户都需要（只读展示）
  model.load()
  // 供应商注册表为 ②（停用状态） / ③（表格） / ④（评测候选）共用，ADMIN 进入页面时预取一次
  if (canWrite.value) registry.load()
})
</script>
