<template>
  <!-- ⑥ 生效范围 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.scope.title') }}</div>
    </div>
    <div class="panel-body">
      <ul class="scope-list">
        <li v-for="item in scope" :key="item.name" class="scope-item">
          <el-icon :size="15" :class="item.follow ? 'scope-yes' : 'scope-no'">
            <component :is="item.follow ? CircleCheckFilled : RemoveFilled" />
          </el-icon>
          <span class="scope-name">{{ item.name }}</span>
          <span class="tiny dim">{{ item.note }}</span>
        </li>
      </ul>

      <div class="code-block mono">
        <div class="code-title">{{ t('settings.scope.envTitle') }}</div>
        <pre>
# {{ t('settings.scope.envKeyComment') }}
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# {{ t('settings.scope.envOptionalComment') }}
# APP_SCREEN_PROVIDER=deepseek
# DEEPSEEK_SCREEN_MODEL=deepseek-flash</pre>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from '../../i18n'
import { CircleCheckFilled, RemoveFilled } from '../../components/icons.js'

const { t } = useI18n()

/** 生效范围：文案随语言切换（t 放在 computed 里，避免顶层固化语言） */
const scope = computed(() => [
  {
    name: t('settings.scope.aiScreening'),
    follow: true,
    note: t('settings.scope.aiScreeningNote')
  },
  { name: t('settings.scope.mining'), follow: true, note: t('settings.scope.miningNote') },
  {
    name: t('settings.scope.transcription'),
    follow: false,
    note: t('settings.scope.transcriptionNote')
  },
  { name: t('settings.scope.dfa'), follow: false, note: t('settings.scope.dfaNote') }
])
</script>

<style scoped>
/* 生效范围 */
.scope-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.scope-item {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
}
.scope-yes {
  color: var(--ok);
}
.scope-no {
  color: var(--ink-400);
}
.scope-name {
  min-width: 132px;
  color: var(--ink-700);
  font-weight: 500;
}
.code-block {
  margin-top: 15px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  padding: 12px 14px;
}
.code-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.code-block pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-600);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
