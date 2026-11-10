<template>
  <!-- 通知与抽样（ADMIN）：容器整理，功能全部保留在既有 NotificationCard -->
  <div class="stack">
    <NotificationCard v-if="canWrite" />
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

// ADMIN 组件保持 defineAsyncComponent 按需加载
const NotificationCard = defineAsyncComponent(() => import('./NotificationCard.vue'))
</script>
