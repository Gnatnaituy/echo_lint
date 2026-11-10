<template>
  <!--
    设置页统一的底部操作行：左侧 dirty / 提示文案，右侧「测试」「保存并生效」按钮。
    复筛模型 / 语音转写 / 知识增强三页共用；testLabel 为空时不渲染测试按钮（如 RAG 只在状态行测连接）。
  -->
  <div class="settings-action-bar">
    <div v-if="hint" class="sab-hint tiny" :class="hintTone === 'warn' ? 'warn-text' : 'dim'">
      {{ hint }}
    </div>
    <div class="sab-actions">
      <el-button
        v-if="testLabel"
        :loading="testLoading"
        :disabled="testDisabled"
        @click="$emit('test')"
      >
        <el-icon v-if="!testLoading" class="el-icon--left"><Connection /></el-icon>
        {{ testLabel }}
      </el-button>
      <el-button
        v-if="saveLabel"
        type="primary"
        :loading="saveLoading"
        :disabled="saveDisabled"
        @click="$emit('save')"
      >
        {{ saveLabel }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { Connection } from '../../components/icons.js'

defineProps({
  /** 左侧 dirty / 状态提示（为空时不渲染） */
  hint: { type: String, default: '' },
  /** dim（默认）| warn（未就绪等警示） */
  hintTone: { type: String, default: 'dim' },
  testLabel: { type: String, default: '' },
  saveLabel: { type: String, default: '' },
  testLoading: { type: Boolean, default: false },
  saveLoading: { type: Boolean, default: false },
  testDisabled: { type: Boolean, default: false },
  saveDisabled: { type: Boolean, default: false }
})

defineEmits(['test', 'save'])
</script>

<style scoped>
.settings-action-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.sab-hint {
  flex: 1;
  min-width: 200px;
  line-height: 1.7;
}
.sab-actions {
  display: flex;
  gap: 10px;
  margin-left: auto;
}
@media (max-width: 640px) {
  .settings-action-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .sab-actions {
    justify-content: flex-end;
  }
}
.warn-text {
  color: #b06a06;
}
.dim {
  color: #64748b;
}
</style>
