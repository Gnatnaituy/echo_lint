<template>
  <!--
    AI 复筛模型（三合一）：当前生效状态行 + 供应商网格（选择 / 编辑 / 新增）+ 模型下拉 + 测试 / 保存。
    「选择的模型就是生效模型」，因此不再拆「当前生效 / 切换 / 供应商管理」三张卡。
    非 ADMIN 只读：仅状态行 + 只读提示，不加载供应商注册表。
  -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.model.title') }}</div>
      <span class="tiny dim">{{ t('settings.model.subtitle') }}</span>
    </div>
    <div class="panel-body">
      <!-- 当前生效状态行 -->
      <div v-if="view" class="active-card">
        <div class="active-left">
          <div class="active-mark" :class="view.apiKeyConfigured ? 'ok' : 'warn'">
            <el-icon :size="19"><Cpu /></el-icon>
          </div>
          <div class="active-info">
            <div class="active-line">
              <span class="active-hint tiny">{{ t('settings.model.current') }}</span>
              <span class="active-provider">{{
                view.providerLabel || view.providerId || '—'
              }}</span>
              <span class="active-sep">·</span>
              <span class="active-model mono">{{ view.model || '—' }}</span>
              <span class="chip" :class="view.source === 'DATABASE' ? 'brand' : 'info'">
                {{
                  view.source === 'DATABASE'
                    ? t('settings.source.uiSwitch')
                    : t('settings.source.configDefault')
                }}
              </span>
              <span class="chip" :class="view.apiKeyConfigured ? 'ok' : 'warn'">
                {{
                  view.apiKeyConfigured
                    ? t('settings.model.keyConfigured')
                    : t('settings.model.keyMissing')
                }}
              </span>
            </div>
          </div>
        </div>
        <el-button
          v-if="canWrite"
          :loading="testing === 'active'"
          :disabled="testing === 'draft'"
          @click="testActive"
        >
          <el-icon v-if="testing !== 'active'" class="el-icon--left"><Connection /></el-icon>
          {{ t('settings.model.testActive') }}
        </el-button>
      </div>
      <div v-else class="empty-block">{{ t('common.loading') }}</div>

      <!-- 连通性测试结果（当前生效 / 待保存选择共用） -->
      <div v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'bad'">
        <el-icon :size="16">
          <component :is="testResult.ok ? CircleCheckFilled : CircleCloseFilled" />
        </el-icon>
        <div class="test-text">
          <div class="test-head">
            <b>{{ testResult.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
            <span class="mono dim">
              {{ testResult.providerLabel }}{{ testResult.model ? ` · ${testResult.model}` : '' }}
            </span>
            <span v-if="testResult.ok && testResult.latencyMs != null" class="num dim">
              {{ t('settings.test.latency', { ms: testResult.latencyMs }) }}
            </span>
          </div>
          <div class="test-msg">
            {{
              testResult.ok ? testResult.reply || t('settings.test.emptyReply') : testResult.message
            }}
          </div>
        </div>
      </div>

      <!-- ADMIN：供应商网格 + 模型 + 操作 -->
      <template v-if="canWrite">
        <div class="block-head">
          <div class="block-title">{{ t('settings.model.chooseProvider') }}</div>
          <span class="tiny dim">{{ t('settings.model.chooseProviderHint') }}</span>
        </div>

        <!-- 注册表不可用时降级：仍可用 screen-model 的供应商列表切换，但无法编辑配置 -->
        <div v-if="providersError" class="provider-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="provider-fallback-text">
            <div class="strong">{{ t('settings.provider.unavailable') }}</div>
            <div class="tiny dim">{{ providersError }}</div>
          </div>
          <el-button size="small" @click="reloadProviders">{{ t('common.retry') }}</el-button>
        </div>

        <div v-if="providersLoading && !providerCards.length" class="empty-block">
          {{ t('common.loading') }}
        </div>
        <div v-else-if="providerCards.length" class="provider-grid">
          <div v-for="card in providerCards" :key="card.key" class="provider-cell">
            <button
              type="button"
              class="provider-card"
              :class="{ on: card.selected, off: card.disabled, current: card.current }"
              :disabled="card.disabled"
              :title="card.disabled ? disabledReasonText(card) : card.baseUrl"
              :aria-pressed="card.selected ? 'true' : 'false'"
              @click="selectProvider(card)"
            >
              <div class="pc-head">
                <span class="pc-name">{{ card.label || card.key }}</span>
                <span v-if="card.current" class="chip brand">{{
                  t('settings.provider.current')
                }}</span>
              </div>
              <div class="pc-url mono">{{ card.baseUrl || '—' }}</div>
              <div class="pc-foot">
                <span
                  class="dot"
                  :style="{ background: card.keyConfigured ? 'var(--ok)' : 'var(--warn)' }"
                ></span>
                <span class="tiny">{{ keyText(card) }}</span>
                <span v-if="card.builtIn" class="chip info pc-builtin">{{
                  t('settings.provider.builtIn')
                }}</span>
                <span v-if="card.models.length" class="tiny dim pc-models">
                  {{ t('settings.provider.modelCount', { count: card.models.length }) }}
                </span>
              </div>
              <div v-if="card.disabledReason" class="pc-reason tiny">
                {{ disabledReasonText(card) }}
              </div>
            </button>

            <!-- 编辑 / 删除：浮在卡片右上角（与选择按钮是兄弟节点，避免嵌套按钮） -->
            <div v-if="!card.fallback" class="pc-actions">
              <el-tooltip :content="t('common.edit')" placement="top">
                <button
                  type="button"
                  class="pc-action"
                  :aria-label="t('settings.provider.editTitle')"
                  @click="openEdit(card)"
                >
                  <el-icon :size="13"><Edit /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip
                v-if="!card.builtIn"
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
                  @click="removeProvider(card)"
                >
                  <el-icon :size="13"><Delete /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>

          <!-- 新增供应商 -->
          <button type="button" class="provider-card add-card" @click="openCreate">
            <el-icon :size="16"><Plus /></el-icon>
            <span>{{ t('settings.provider.create') }}</span>
          </button>
        </div>
        <div v-else class="empty-block">
          <div class="empty-title">{{ t('settings.provider.empty') }}</div>
          <div class="tiny dim">{{ t('settings.provider.emptyHint') }}</div>
        </div>

        <!-- 模型 + 测试 / 保存 -->
        <div class="switch-row">
          <div class="field">
            <label class="field-label">{{ t('settings.model.label') }}</label>
            <el-select
              v-model="draft.model"
              class="model-select"
              filterable
              allow-create
              default-first-option
              :placeholder="t('settings.model.placeholder')"
            >
              <el-option v-for="m in modelOptions" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="switch-actions">
            <el-button
              :loading="testing === 'draft'"
              :disabled="!draft.providerId || draftDisabled"
              @click="testDraft"
            >
              {{ t('settings.model.test') }}
            </el-button>
            <el-button
              type="primary"
              :loading="saving"
              :disabled="!dirty || draftDisabled"
              @click="save"
            >
              {{ t('settings.model.save') }}
            </el-button>
          </div>
        </div>
        <div v-if="draftDisabled && draftReason" class="hint tiny warn-text">
          {{ draftReason }}
        </div>
        <div class="hint tiny dim">
          {{ t('settings.model.hintSelect') }}
          {{ t('settings.model.hintEffect') }}
        </div>
      </template>

      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        :title="t('settings.model.readonlyAlert')"
      />
    </div>

    <!-- 供应商新增 / 编辑：仅 ADMIN 渲染并按需异步加载（非管理员不下载该 chunk） -->
    <ProviderFormDialog v-if="canWrite" ref="formDialog" @saved="refreshAll" />
  </section>
</template>

<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import { mergeScreeningProviders } from '../../utils/providers'
import { useSettingsContext } from './context'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Connection,
  Cpu,
  Delete,
  Edit,
  Plus,
  WarningFilled
} from '../../components/icons.js'

const { t } = useI18n()
const { canWrite, model, registry } = useSettingsContext()
const {
  view,
  draft,
  dirty,
  saving,
  testing,
  testResult,
  testActive,
  testDraft,
  save,
  pickProvider
} = model
const { providers, providersLoading, providersError, load: loadProviders } = registry

// 供应商表单对话框：ADMIN 才渲染 → defineAsyncComponent 的 chunk 对普通用户不下载
const ProviderFormDialog = defineAsyncComponent(() => import('./ProviderFormDialog.vue'))

const formDialog = ref(null)

/** 注册表 + screen-model 合并成网格卡片：当前生效 / 当前选中 / 停用 / 未配 Key 都在这层算好 */
const providerCards = computed(() =>
  mergeScreeningProviders({
    providers: providers.value,
    view: view.value,
    selectedKey: draft.providerId
  })
)

const selectedCard = computed(() => providerCards.value.find((card) => card.selected) || null)

const draftDisabled = computed(() => selectedCard.value?.disabled === true)
const draftReason = computed(() => {
  const card = selectedCard.value
  return card?.disabledReason ? disabledReasonText(card) : ''
})

/** 模型候选：优先取当前选中供应商的候选列表（保留手填） */
const modelOptions = computed(() => {
  const models = selectedCard.value?.models || []
  if (draft.model && !models.includes(draft.model)) return [draft.model, ...models]
  return models
})

/** 卡片上的 Key 文案：只显示后端掩码，绝不接触明文 */
function keyText(card) {
  if (card.keyConfigured === false) return t('settings.state.notConfigured')
  return card.keyMasked || t('settings.state.configured')
}

function disabledReasonText(card) {
  if (card.disabledReason === 'disabled') return t('settings.model.providerDisabled')
  if (card.disabledReason === 'missingKey') return t('settings.model.providerMissingKey')
  return ''
}

function selectProvider(card) {
  if (card.disabled) return
  pickProvider({ id: card.key, defaultModel: card.defaultModel, models: card.models })
}

function openCreate() {
  formDialog.value?.open(null)
}

function openEdit(card) {
  // 编辑弹窗需要注册表中的完整行（chatPath / 价格 / 最大 tokens 等），按 key 回查
  const row = providers.value.find(
    (item) => String(item.key || '').toLowerCase() === String(card.key || '').toLowerCase()
  )
  if (row) formDialog.value?.open(row)
}

async function removeProvider(card) {
  try {
    await ElMessageBox.confirm(
      t('settings.provider.deleteConfirm'),
      t('settings.provider.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel')
      }
    )
  } catch {
    return
  }
  try {
    await api.deleteProvider(card.key)
    ElMessage.success(t('settings.provider.deleted', { label: card.label || card.key }))
    await refreshAll()
  } catch {
    // 请求层已统一提示（仅自定义、非当前生效可删）
  }
}

/** 供应商增删改后刷新注册表与当前生效视图，保证状态行 / 网格 / 模型候选一致 */
async function refreshAll() {
  await Promise.all([model.load(), loadProviders()])
}

async function reloadProviders() {
  await loadProviders()
}
</script>

<style scoped>
/* 当前生效状态行 */
.active-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.active-left {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.active-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--r-md);
}
.active-mark.ok {
  background: var(--brand-50);
  color: var(--brand-600);
}
.active-mark.warn {
  background: var(--warn-soft);
  color: #b06a06;
}
.active-info {
  min-width: 0;
}
.active-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.active-hint {
  color: #64748b;
}
.active-provider {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
}
.active-sep {
  color: var(--ink-300);
}
.active-model {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-600);
}
@media (max-width: 720px) {
  .active-card {
    flex-wrap: wrap;
    gap: 10px;
  }
}

/* 测试结果 */
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

/* 供应商选择区 */
.block-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 18px;
  margin-bottom: 10px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}
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
  background: #fff;
  cursor: pointer;
  transition: all 0.18s ease;
  font: inherit;
}
.provider-card:hover:not(:disabled) {
  border-color: var(--brand-300);
  transform: translateY(-1px);
  box-shadow: var(--sh-xs);
}
.provider-card.on {
  border-color: var(--brand-500);
  background: var(--brand-50);
  box-shadow: 0 0 0 3px rgba(59, 110, 246, 0.1);
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
}
.pc-models {
  margin-left: auto;
}
.pc-builtin {
  height: 19px;
  padding: 0 6px;
  font-size: 11px;
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

/* 模型 + 操作 */
.switch-row {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.field {
  min-width: 260px;
}
.field-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.model-select {
  width: 280px;
}
.switch-actions {
  display: flex;
  gap: 10px;
}
.hint {
  margin-top: 10px;
  line-height: 1.7;
}
.warn-text {
  color: #b06a06;
}
</style>
