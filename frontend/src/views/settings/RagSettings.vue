<template>
  <!-- 知识增强（RAG，ADMIN）：只做容器与标题整理，功能全部保留在既有 RagCard -->
  <div class="stack">
    <RagCard v-if="canWrite" />
    <el-alert
      v-else
      type="warning"
      :closable="false"
      show-icon
      :title="t('settings.error.forbidden')"
    />
  </div>
</template>

<script setup>
import { defineAsyncComponent } from 'vue'
import { useI18n } from '../../i18n'
import { useSettingsContext } from './context'

const { t } = useI18n()
const { canWrite } = useSettingsContext()

// ADMIN 组件保持 defineAsyncComponent 按需加载（路由本身也已是懒加载页面）
const RagCard = defineAsyncComponent(() => import('./RagCard.vue'))
</script>
