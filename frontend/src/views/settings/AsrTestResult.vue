<template>
  <!-- ASR 连通性测试结果条：当前生效卡片与两张配置卡共用同一套展示 -->
  <div v-if="result" class="test-result" :class="result.ok ? 'ok' : 'bad'">
    <el-icon :size="16">
      <component :is="result.ok ? CircleCheckFilled : CircleCloseFilled" />
    </el-icon>
    <div class="test-text">
      <div class="test-head">
        <b>{{ result.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
        <span class="mono dim">
          {{ providerLabel }}{{ result.model ? ` · ${result.model}` : '' }}
        </span>
        <span v-if="result.ok && result.latencyMs !== null" class="num dim">
          {{ t('settings.test.latency', { ms: result.latencyMs }) }}
        </span>
      </div>
      <div class="test-msg">
        {{ result.message || (result.ok ? t('settings.test.emptyReply') : '') }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { useI18n } from '../../i18n'
import { CircleCheckFilled, CircleCloseFilled } from '../../components/icons.js'

defineProps({
  /** normalizeAsrTest 的结果对象：{ok, provider, model, latencyMs, message} */
  result: { type: Object, default: null },
  /** provider 展示名（由父组件按当前语言翻译） */
  providerLabel: { type: String, default: '' }
})

const { t } = useI18n()
</script>

<style scoped>
.test-result {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  font-size: 12.5px;
  line-height: 1.7;
}
.test-result.ok {
  background: var(--ok-soft);
  color: #14672f;
}
.test-result.bad {
  background: var(--danger-soft);
  color: #a32424;
}
.test-text {
  min-width: 0;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.test-msg {
  margin-top: 2px;
  word-break: break-all;
  opacity: 0.85;
}
</style>
