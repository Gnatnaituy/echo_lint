<template>
  <!--
    设置页统一的「供应商卡片网格」：复筛模型 / 语音转写 / 知识增强（Embedding）共用。
    数据来自 utils/providers.js 的 mergeScreeningProviders（key/label/baseUrl/models/
    keyConfigured/keyMasked/enabled/disabledReason/current/selected/builtIn/ready）。
    - editable：卡片右上角「编辑」；removable：仅自定义供应商显示「删除」；
    - creatable：网格末尾「新增供应商」卡片；
    - allowUnconfigured：未配置 Key 的卡片不置灰禁用（如语音转写：可选中后在下方填写 Key）。
  -->
  <div class="provider-card-grid">
    <div v-if="error" class="provider-fallback">
      <el-icon :size="16"><WarningFilled /></el-icon>
      <div class="provider-fallback-text">
        <div class="strong">{{ t('settings.provider.unavailable') }}</div>
        <div class="tiny dim">{{ error }}</div>
      </div>
      <el-button size="small" @click="$emit('retry')">{{ t('common.retry') }}</el-button>
    </div>

    <div v-if="loading && !cards.length" class="empty-block">{{ t('common.loading') }}</div>
    <div v-else-if="cards.length" class="provider-grid">
      <div v-for="card in cards" :key="card.key" class="provider-cell">
        <button
          type="button"
          class="provider-card"
          :class="{
            on: card.selected,
            off: isDisabled(card),
            muted: isMuted(card),
            current: card.current
          }"
          :disabled="isDisabled(card)"
          :title="titleOf(card)"
          :aria-pressed="card.selected ? 'true' : 'false'"
          @click="onSelect(card)"
        >
          <div class="pc-head">
            <span class="pc-name">{{ card.label || card.key }}</span>
            <span v-if="card.current" class="chip brand">{{ t('settings.provider.current') }}</span>
          </div>
          <div class="pc-url mono">{{ card.baseUrl || '—' }}</div>
          <div class="pc-foot">
            <span
              class="dot"
              :style="{ background: card.keyConfigured ? 'var(--ok)' : 'var(--warn)' }"
            ></span>
            <span class="tiny">{{ keyText(card) }}</span>
            <span
              v-if="showReady && card.ready !== null && card.ready !== undefined"
              class="chip pc-chip"
              :class="card.ready ? 'ok' : 'warn'"
            >
              {{ card.ready ? t('settings.provider.ready') : t('settings.provider.notReady') }}
            </span>
            <span v-if="card.builtIn" class="chip info pc-chip">{{
              t('settings.provider.builtIn')
            }}</span>
            <span v-if="showModelCount && card.models.length" class="tiny dim pc-models">
              {{ t('settings.provider.modelCount', { count: card.models.length }) }}
            </span>
          </div>
          <div v-if="reasonOf(card)" class="pc-reason tiny">{{ reasonOf(card) }}</div>
        </button>

        <!-- 编辑 / 删除：浮在卡片右上角（与选择按钮是兄弟节点，避免嵌套按钮） -->
        <div v-if="editable || (removable && !card.builtIn)" class="pc-actions">
          <el-tooltip v-if="editable && !card.fallback" :content="t('common.edit')" placement="top">
            <button
              type="button"
              class="pc-action"
              :aria-label="t('settings.provider.editTitle')"
              @click="$emit('edit', card)"
            >
              <el-icon :size="13"><Edit /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip
            v-if="removable && !card.builtIn && !card.fallback"
            :content="
              card.current ? t('settings.provider.activeDeleteTooltip') : t('common.delete')
            "
            placement="top"
          >
            <button
              type="button"
              class="pc-action danger"
              :disabled="card.current"
              :aria-label="t('common.delete')"
              @click="$emit('remove', card)"
            >
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>

      <!-- 新增供应商 -->
      <button
        v-if="creatable"
        type="button"
        class="provider-card add-card"
        @click="$emit('create')"
      >
        <el-icon :size="16"><Plus /></el-icon>
        <span>{{ t('settings.provider.create') }}</span>
      </button>
    </div>
    <div v-else class="empty-block">
      <div class="empty-title">{{ t('settings.provider.empty') }}</div>
      <div class="tiny dim">{{ t('settings.provider.emptyHint') }}</div>
    </div>
  </div>
</template>

<script setup>
import { useI18n } from '../../i18n'
import { Delete, Edit, Plus, WarningFilled } from '../../components/icons.js'

const props = defineProps({
  /** mergeScreeningProviders 输出的卡片数组 */
  cards: { type: Array, required: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  /** 右上角「编辑」动作（ADMIN 且注册表可用时） */
  editable: { type: Boolean, default: false },
  /** 仅自定义供应商的「删除」动作 */
  removable: { type: Boolean, default: false },
  /** 网格末尾「新增供应商」卡片 */
  creatable: { type: Boolean, default: false },
  /** 未配置 Key 的卡片保持可选中（选中后在配置表单里填写并保存） */
  allowUnconfigured: { type: Boolean, default: false },
  /** 卡片底部显示「就绪 / 未就绪」胶囊（语音转写用） */
  showReady: { type: Boolean, default: false },
  showModelCount: { type: Boolean, default: true },
  /** 自定义不可选原因文案：card => string；缺省用复筛页文案 */
  reasonText: { type: Function, default: null }
})

const emit = defineEmits(['select', 'edit', 'remove', 'create', 'retry'])
const { t } = useI18n()

function isDisabled(card) {
  if (!card.disabled) return false
  // 允许选中未配置 Key 的卡片：选中后在下方配置表单填写并保存
  return !(props.allowUnconfigured && card.disabledReason === 'missingKey')
}

/** 灰显但可点选（未配置 Key 的服务/供应商） */
function isMuted(card) {
  return props.allowUnconfigured && card.disabledReason === 'missingKey'
}

function keyText(card) {
  if (card.keyConfigured === false) return t('settings.state.notConfigured')
  return card.keyMasked || t('settings.state.configured')
}

function reasonOf(card) {
  if (!card.disabledReason) return ''
  if (props.reasonText) return props.reasonText(card)
  if (card.disabledReason === 'disabled') return t('settings.model.providerDisabled')
  if (card.disabledReason === 'missingKey') return t('settings.model.providerMissingKey')
  return ''
}

function titleOf(card) {
  if (isDisabled(card)) return reasonOf(card)
  return card.baseUrl || ''
}

function onSelect(card) {
  if (isDisabled(card)) return
  emit('select', card)
}
</script>

<style scoped>
.provider-fallback {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  color: var(--el-text-color-secondary);
}
.provider-fallback-text {
  min-width: 0;
  flex: 1;
}
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(226px, 1fr));
  gap: 12px;
}
.provider-cell {
  position: relative;
  min-width: 0;
}
.provider-card {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  min-height: 96px;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fff 0%, #fcfdff 100%);
  box-shadow: var(--sh-xs);
  cursor: pointer;
  transition: all 0.18s ease;
  font: inherit;
}
.provider-card:hover:not(:disabled) {
  border-color: var(--brand-300);
  transform: translateY(-1px);
  box-shadow: var(--sh-sm);
}
.provider-card.on {
  border-color: var(--brand-500);
  background: linear-gradient(180deg, #f4f7ff 0%, var(--brand-50) 100%);
  box-shadow:
    0 0 0 3px rgba(59, 110, 246, 0.1),
    var(--sh-xs);
}
.provider-card.current::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: var(--r-md);
  border: 1px dashed var(--brand-300);
  pointer-events: none;
}
.provider-card.off {
  opacity: 0.62;
  cursor: not-allowed;
}
.provider-card.off:hover {
  transform: none;
  border-color: var(--border);
  box-shadow: none;
}
/* 未配置 Key 但允许选中：灰显提示，仍可点击（选中后填写并保存） */
.provider-card.muted {
  opacity: 0.75;
}
.provider-card.muted:hover {
  border-color: var(--brand-300);
}
.pc-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding-right: 42px;
}
.pc-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
}
.pc-url {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pc-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}
.pc-chip {
  height: 19px;
  padding: 0 6px;
  font-size: 11px;
}
.pc-models {
  margin-left: auto;
}
.pc-reason {
  margin-top: 6px;
  color: #b06a06;
  line-height: 1.5;
}
.pc-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  gap: 2px;
}
.pc-action {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.pc-action:hover:not(:disabled) {
  background: #fff;
  border-color: var(--border);
  color: var(--brand-600);
}
.pc-action.danger:hover:not(:disabled) {
  color: var(--danger);
}
.pc-action:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-style: dashed;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}
.add-card:hover {
  color: var(--brand-600);
  border-color: var(--brand-300);
}
</style>
