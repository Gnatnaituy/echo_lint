<template>
  <!--
    AI 复筛模型（三合一）：当前生效状态行 + 供应商卡片网格（选择 / 编辑 / 新增 / 删除）+ 模型下拉 + 测试 / 保存。
    「选择的模型就是生效模型」，因此不再拆「当前生效 / 切换 / 供应商管理」多张卡。
    状态行 / 卡片网格 / 操作行使用设置页共享组件，与语音转写、知识增强保持一致。
    非 ADMIN 只读：仅状态行 + 只读提示，不加载供应商注册表。
  -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.model.title') }}</div>
      <span class="tiny dim">{{ t('settings.model.subtitle') }}</span>
    </div>
    <div class="panel-body">
      <!-- 当前生效状态行 -->
      <ConfigStatusBar
        v-if="view"
        :label="t('settings.model.current')"
        :provider="view.providerLabel || view.providerId"
        :model="view.model"
        :tone="view.apiKeyConfigured ? 'ok' : 'warn'"
        :icon="Cpu"
      >
        <template #chips>
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
        </template>
        <template #actions>
          <el-button
            v-if="canWrite"
            :loading="testing === 'active'"
            :disabled="testing === 'draft'"
            @click="testActive"
          >
            <el-icon v-if="testing !== 'active'" class="el-icon--left"><Connection /></el-icon>
            {{ t('settings.model.testActive') }}
          </el-button>
        </template>
      </ConfigStatusBar>
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

        <ProviderCardGrid
          :cards="providerCards"
          :loading="providersLoading"
          :error="providersError"
          editable
          removable
          creatable
          :reason-text="disabledReasonText"
          @select="selectProvider"
          @edit="openEdit"
          @create="openCreate"
          @remove="removeProvider"
          @retry="loadProviders"
        />

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
        </div>

        <SettingsActionBar
          :hint="actionHint"
          :hint-tone="draftDisabled ? 'warn' : 'dim'"
          :test-label="t('settings.model.test')"
          :save-label="t('settings.model.save')"
          :test-loading="testing === 'draft'"
          :test-disabled="!draft.providerId || draftDisabled"
          :save-loading="saving"
          :save-disabled="!dirty || draftDisabled"
          @test="testDraft"
          @save="save"
        />
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
import { CircleCheckFilled, CircleCloseFilled, Connection, Cpu } from '../../components/icons.js'
import ConfigStatusBar from './ConfigStatusBar.vue'
import ProviderCardGrid from './ProviderCardGrid.vue'
import SettingsActionBar from './SettingsActionBar.vue'

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

const actionHint = computed(() => {
  if (draftDisabled.value && draftReason.value) return draftReason.value
  return `${t('settings.model.hintSelect')} ${t('settings.model.hintEffect')}`
})

/** 模型候选：优先取当前选中供应商的候选列表（保留手填） */
const modelOptions = computed(() => {
  const models = selectedCard.value?.models || []
  if (draft.model && !models.includes(draft.model)) return [draft.model, ...models]
  return models
})

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
</script>

<style scoped>
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

/* 模型选择 */
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
</style>
