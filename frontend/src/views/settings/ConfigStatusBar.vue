<template>
  <!--
    设置页统一的「当前生效状态行」：左侧图标 + 当前生效 provider · model + chips，右侧 actions 插槽。
    复筛模型 / 语音转写 / 知识增强（Embedding）三页共用，保证状态行视觉一致。
  -->
  <div class="config-status-bar" :class="tone">
    <div class="csb-left">
      <div class="csb-mark" :class="tone">
        <el-icon :size="19"><component :is="icon" /></el-icon>
      </div>
      <div class="csb-info">
        <div class="csb-line">
          <span class="csb-label tiny">{{ label }}</span>
          <span class="csb-provider">{{ provider || '—' }}</span>
          <span class="csb-sep">·</span>
          <span class="csb-model mono">{{ model || '—' }}</span>
          <slot name="chips" />
        </div>
        <slot name="note" />
      </div>
    </div>
    <div v-if="$slots.actions" class="csb-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
import { Cpu } from '../../components/icons.js'

defineProps({
  /** 「当前生效 / Active」等前缀文案（由各页传入，便于 i18n 分组） */
  label: { type: String, default: '' },
  provider: { type: String, default: '' },
  model: { type: String, default: '' },
  /** ok：已就绪 / 已配置；warn：未配置或未就绪 */
  tone: { type: String, default: 'ok' },
  /** 左侧图标组件（默认 CPU；转写用 Microphone，Embedding 用 Connection） */
  icon: { type: [Object, Function], default: Cpu }
})
</script>

<style scoped>
.config-status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.csb-left {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.csb-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--r-md);
}
.csb-mark.ok {
  background: var(--brand-50);
  color: var(--brand-600);
}
.csb-mark.warn {
  background: var(--warn-soft);
  color: #b06a06;
}
.csb-info {
  min-width: 0;
}
.csb-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.csb-label {
  color: var(--ink-500);
}
.csb-provider {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
}
.csb-sep {
  color: var(--ink-300);
}
.csb-model {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-600);
  word-break: break-all;
}
.csb-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
@media (max-width: 720px) {
  .config-status-bar {
    flex-wrap: wrap;
    gap: 10px;
  }
}
</style>
