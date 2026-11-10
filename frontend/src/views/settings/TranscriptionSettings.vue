<template>
  <!--
    语音转写（单卡）：状态行（当前生效 provider / 模型 / Key 掩码 / 测试）+ 服务切换 +
    仅选中 provider 的配置面板 + 一组「测试连接 / 保存并生效」+ 说明。
    密钥只显示掩码；输入框留空=不修改。契约见 docs/asr-and-settings-refactor.md §6.3。
  -->
  <div class="stack">
    <el-alert
      v-if="!canWrite"
      type="warning"
      :closable="false"
      show-icon
      :title="t('settings.asr.forbidden')"
    />

    <section v-else class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.asr.title') }}</div>
        <span class="tiny dim">{{ t('settings.asr.subtitle') }}</span>
      </div>
      <div class="panel-body">
        <div v-if="loading" class="empty-block">{{ t('common.loading') }}</div>

        <div v-else-if="loadError" class="asr-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="asr-fallback-text">
            <div class="strong">{{ t('settings.asr.unavailable') }}</div>
            <div class="tiny dim">{{ loadError }}</div>
          </div>
          <el-button size="small" @click="loadSettings">{{ t('common.retry') }}</el-button>
        </div>

        <template v-else-if="view">
          <!-- ① 当前生效状态行（与复筛模型 / 知识增强共用 ConfigStatusBar） -->
          <ConfigStatusBar
            :label="t('settings.asr.active.label')"
            :provider="providerLabel(activeProvider)"
            :model="activeModel"
            :tone="activeReady ? 'ok' : 'warn'"
            :icon="Microphone"
          >
            <template #chips>
              <span class="chip" :class="activeBlock?.apiKeyConfigured === false ? 'warn' : 'ok'">
                {{ activeKeyText }}
              </span>
            </template>
            <template #actions>
              <el-button
                :loading="testing === activeProvider"
                :disabled="!activeProvider || (testing !== null && testing !== activeProvider)"
                @click="runTest(activeProvider)"
              >
                <el-icon v-if="testing !== activeProvider" class="el-icon--left"
                  ><Connection
                /></el-icon>
                {{ t('settings.asr.test') }}
              </el-button>
            </template>
          </ConfigStatusBar>
          <AsrTestResult
            :result="testResults[activeProvider]"
            :provider-label="providerLabel(activeProvider)"
          />

          <!-- ② 选择服务：供应商卡片网格（与复筛模型一致；未就绪可选中后填 Key） -->
          <div class="block-head">
            <div class="block-title">{{ t('settings.asr.switch.title') }}</div>
            <span class="tiny dim">{{ t('settings.asr.switch.chooseProviderHint') }}</span>
          </div>
          <ProviderCardGrid
            :cards="providerCards"
            allow-unconfigured
            show-ready
            :show-model-count="false"
            :reason-text="asrReasonText"
            @select="selectProvider"
          />

          <!-- ③ 仅显示选中 provider 的配置 -->
          <div class="config-panel">
            <div class="config-head">
              <div class="block-title">{{ configTitle }}</div>
              <span class="tiny dim">{{ configSubtitle }}</span>
            </div>

            <template v-if="draftProvider === ASR_PROVIDER_LOCAL">
              <div class="asr-fields">
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.local.baseUrl') }}</label>
                  <el-input
                    v-model="localForm.baseUrl"
                    :placeholder="ASR_DEFAULT_LOCAL_BASE_URL"
                    clearable
                  />
                </div>
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.local.model') }}</label>
                  <el-input
                    v-model="localForm.model"
                    :placeholder="ASR_DEFAULT_LOCAL_MODEL"
                    clearable
                  />
                </div>
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.local.apiKey') }}</label>
                  <el-input
                    v-model="localForm.apiKey"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    :placeholder="apiKeyPlaceholder(view.local)"
                  />
                </div>
              </div>
              <div class="hint tiny dim">{{ t('settings.asr.local.note') }}</div>
            </template>

            <template v-else>
              <div class="asr-fields">
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.dashscope.apiKey') }}</label>
                  <el-input
                    v-model="dashForm.apiKey"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    :placeholder="apiKeyPlaceholder(view.dashscope)"
                  />
                </div>
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.dashscope.model') }}</label>
                  <el-select
                    v-model="dashForm.model"
                    class="full-width"
                    filterable
                    allow-create
                    default-first-option
                    :placeholder="ASR_DEFAULT_DASHSCOPE_MODEL"
                  >
                    <el-option
                      v-for="model in dashModelOptions"
                      :key="model"
                      :label="model"
                      :value="model"
                    />
                  </el-select>
                  <div class="field-hint tiny dim">
                    {{ t('settings.asr.dashscope.modelHint') }}
                  </div>
                </div>
                <div class="field">
                  <label class="field-label">{{ t('settings.asr.dashscope.baseUrl') }}</label>
                  <el-input
                    v-model="dashForm.baseUrl"
                    :placeholder="ASR_DEFAULT_DASHSCOPE_BASE_URL"
                    clearable
                  />
                  <div class="field-hint tiny dim">
                    {{ t('settings.asr.dashscope.baseUrlHint') }}
                  </div>
                </div>
                <div class="asr-inline">
                  <div class="field">
                    <label class="field-label">{{
                      t('settings.asr.dashscope.languageHints')
                    }}</label>
                    <el-input
                      v-model="dashForm.languageHints"
                      :placeholder="ASR_DEFAULT_LANGUAGE_HINTS"
                      clearable
                    />
                    <div class="field-hint tiny dim">
                      {{ t('settings.asr.dashscope.languageHintsHint') }}
                    </div>
                  </div>
                  <div class="field">
                    <label class="field-label">{{ t('settings.asr.dashscope.timeout') }}</label>
                    <el-input-number
                      v-model="timeoutSeconds"
                      :min="ASR_TIMEOUT_MIN_SECONDS"
                      :max="ASR_TIMEOUT_MAX_SECONDS"
                      :step="30"
                      controls-position="right"
                      class="full-width"
                    />
                    <div class="field-hint tiny dim">
                      {{
                        t('settings.asr.dashscope.timeoutHint', {
                          min: ASR_TIMEOUT_MIN_SECONDS,
                          max: ASR_TIMEOUT_MAX_SECONDS,
                          default: ASR_TIMEOUT_DEFAULT_SECONDS
                        })
                      }}
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <!-- ④ 统一操作行：测试选中服务 / 保存并生效（切换与配置一次提交） -->
          <SettingsActionBar
            :hint="selectionHint"
            :hint-tone="selectionHintWarn ? 'warn' : 'dim'"
            :test-label="t('settings.asr.test')"
            :save-label="t('settings.asr.save')"
            :test-loading="testing === draftProvider"
            :test-disabled="testing !== null && testing !== draftProvider"
            :save-loading="saving"
            :save-disabled="!canSave"
            @test="runTest(draftProvider)"
            @save="saveSettings"
          />
          <AsrTestResult
            v-if="draftProvider !== activeProvider"
            :result="testResults[draftProvider]"
            :provider-label="providerLabel(draftProvider)"
          />

          <!-- ⑤ 说明 -->
          <ul class="asr-notes">
            <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
          </ul>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import {
  ASR_DEFAULT_DASHSCOPE_BASE_URL,
  ASR_DEFAULT_DASHSCOPE_MODEL,
  ASR_DEFAULT_LANGUAGE_HINTS,
  ASR_DEFAULT_LOCAL_BASE_URL,
  ASR_DEFAULT_LOCAL_MODEL,
  ASR_PROVIDERS,
  ASR_PROVIDER_DASHSCOPE,
  ASR_PROVIDER_LOCAL,
  ASR_TIMEOUT_DEFAULT_SECONDS,
  ASR_TIMEOUT_MAX_SECONDS,
  ASR_TIMEOUT_MIN_SECONDS,
  asrApiKey,
  asrModelCandidates,
  buildAsrUpdatePayload,
  isAsrProvider
} from '../../utils/asr'
import { mergeScreeningProviders } from '../../utils/providers'
import { Connection, Microphone, WarningFilled } from '../../components/icons.js'
import AsrTestResult from './AsrTestResult.vue'
import ConfigStatusBar from './ConfigStatusBar.vue'
import ProviderCardGrid from './ProviderCardGrid.vue'
import SettingsActionBar from './SettingsActionBar.vue'
import { useSettingsContext } from './context'
import { adminErrorMessage } from './adminErrors'

const { t } = useI18n()
const { canWrite } = useSettingsContext()

/** GET /api/settings/asr 的归一化视图（密钥只回掩码） */
const view = ref(null)
const loading = ref(true)
const loadError = ref('')

/** 待切换的 provider（与生效值分离，保存并生效时才提交） */
const draftProvider = ref(ASR_PROVIDER_LOCAL)
const saving = ref(false)

/** 正在测试的 provider（互斥：同一时刻只允许一个测试请求） */
const testing = ref(null)
/** provider -> normalizeAsrTest 结果；状态行显示生效服务的结果，底部显示选中服务的结果 */
const testResults = reactive({})

// 两张 provider 表单都常驻（切换选择时保留已填内容）；apiKey 永远空串=不回填掩码
const localForm = reactive({ baseUrl: '', model: '', apiKey: '' })
const dashForm = reactive({ baseUrl: '', model: '', languageHints: '', apiKey: '' })
const timeoutSeconds = ref(ASR_TIMEOUT_DEFAULT_SECONDS)

const activeProvider = computed(() => view.value?.activeProvider || '')
const activeBlock = computed(() =>
  activeProvider.value ? view.value?.[activeProvider.value] || null : null
)
const activeReady = computed(() => activeBlock.value?.ready === true)
const activeModel = computed(() => activeBlock.value?.model || '')

/** 状态行 Key 胶囊：只显示后端掩码，不显示「来自环境变量」等来源 */
const activeKeyText = computed(() => {
  const block = activeBlock.value
  if (!block) return ''
  if (block.apiKeyConfigured === false) return t('settings.asr.key.notConfigured')
  if (block.apiKeyMasked) return t('settings.asr.key.masked', { masked: block.apiKeyMasked })
  return t('settings.asr.key.configured')
})

/** 服务展示名走 i18n（后端 label 是服务端语言，不直接展示） */
function providerLabel(provider) {
  if (provider === ASR_PROVIDER_LOCAL) return t('settings.asr.provider.local')
  if (provider === ASR_PROVIDER_DASHSCOPE) return t('settings.asr.provider.dashscope')
  return t('settings.asr.unknownProvider')
}

/** 选中 provider 的配置面板标题 / 说明 */
const configTitle = computed(() =>
  draftProvider.value === ASR_PROVIDER_LOCAL
    ? t('settings.asr.local.title')
    : t('settings.asr.dashscope.title')
)
const configSubtitle = computed(() =>
  draftProvider.value === ASR_PROVIDER_LOCAL
    ? t('settings.asr.local.subtitle')
    : t('settings.asr.dashscope.subtitle')
)

/** 该 provider 的配置卡里是否已输入但未保存 API Key */
function pendingKey(provider) {
  if (provider === ASR_PROVIDER_DASHSCOPE) return !!asrApiKey(dashForm.apiKey)
  if (provider === ASR_PROVIDER_LOCAL) return !!asrApiKey(localForm.apiKey)
  return false
}

const draftBlock = computed(() => view.value?.[draftProvider.value] || null)
const draftPendingKey = computed(() => pendingKey(draftProvider.value))

/**
 * 服务卡片：本地 / 百炼两台 provider 用统一卡片网格渲染（就绪状态、Key 掩码、当前生效标记）。
 * providers 为空 → mergeScreeningProviders 走 view.providers 回退分支（只读卡片、不可编辑）。
 */
const providerCards = computed(() =>
  mergeScreeningProviders({
    providers: [],
    view: {
      providerId: activeProvider.value,
      providers: ASR_PROVIDERS.map((provider) => {
        const block = view.value?.[provider]
        return {
          id: provider,
          label: providerLabel(provider),
          baseUrl: block?.baseUrl || '',
          models:
            provider === ASR_PROVIDER_DASHSCOPE
              ? asrModelCandidates(block?.models, block?.model)
              : [block?.model].filter(Boolean),
          ready: block?.ready,
          apiKeyConfigured: block?.apiKeyConfigured,
          apiKeyMasked: block?.apiKeyMasked,
          enabled: true
        }
      })
    },
    selectedKey: draftProvider.value
  })
)

function selectProvider(card) {
  if (isAsrProvider(card.key)) draftProvider.value = card.key
}

/** 未配置 Key 的服务卡片原因（选中后可在此页下方填写并保存） */
function asrReasonText(card) {
  if (card.disabledReason !== 'missingKey') return ''
  return t('settings.asr.switch.missingKey', { provider: card.label || providerLabel(card.key) })
}

function missingKeyHint(provider) {
  return t('settings.asr.switch.missingKey', { provider })
}

const localDirty = computed(() => {
  const block = view.value?.local
  if (!block) return false
  return (
    localForm.baseUrl.trim() !== block.baseUrl ||
    localForm.model.trim() !== block.model ||
    !!asrApiKey(localForm.apiKey)
  )
})

const dashDirty = computed(() => {
  const block = view.value?.dashscope
  if (!block) return false
  const timeout = Number(timeoutSeconds.value ?? ASR_TIMEOUT_DEFAULT_SECONDS)
  return (
    dashForm.baseUrl.trim() !== block.baseUrl ||
    dashForm.model.trim() !== block.model ||
    dashForm.languageHints.trim() !== block.languageHints ||
    timeout !== Number(view.value?.timeoutSeconds ?? ASR_TIMEOUT_DEFAULT_SECONDS) ||
    !!asrApiKey(dashForm.apiKey)
  )
})

const selectedDirty = computed(() =>
  draftProvider.value === ASR_PROVIDER_LOCAL ? localDirty.value : dashDirty.value
)
const switchDirty = computed(
  () => !!view.value && draftProvider.value !== (view.value.activeProvider || '')
)

const canSave = computed(() => {
  if (!view.value || (!selectedDirty.value && !switchDirty.value)) return false
  // 目标服务未就绪且没有新填的 Key：保存会因未就绪被后端拒绝，先提示去填写
  if (!draftBlock.value?.ready && !draftPendingKey.value) return false
  return true
})

const selectionHintWarn = computed(() => !draftBlock.value?.ready && !draftPendingKey.value)

const selectionHint = computed(() => {
  if (!view.value) return ''
  const provider = providerLabel(draftProvider.value)
  if (selectionHintWarn.value) return missingKeyHint(provider)
  if (draftPendingKey.value && !draftBlock.value?.ready) {
    return t('settings.asr.switch.pendingKey', { provider })
  }
  if (switchDirty.value) return t('settings.asr.switch.pendingSwitch', { provider })
  if (selectedDirty.value) return t('settings.asr.switch.dirty')
  return t('settings.asr.switch.current', { provider })
})

const dashModelOptions = computed(() =>
  asrModelCandidates(view.value?.dashscope?.models, dashForm.model)
)

/** 密钥输入框占位：已配置显示掩码并提示留空不改；未配置提示填写 */
function apiKeyPlaceholder(block) {
  if (block?.apiKeyConfigured) {
    return t('settings.asr.key.keep', { masked: block.apiKeyMasked || '****' })
  }
  return t('settings.asr.key.empty')
}

const notes = computed(() => [
  t('settings.asr.notes.local'),
  t('settings.asr.notes.billing'),
  t('settings.asr.notes.upload'),
  t('settings.asr.notes.security')
])

/** 保存成功 / 加载后用响应视图局部刷新（两份表单都同步，清空密钥输入框） */
function applyView(data) {
  view.value = data
  draftProvider.value = data.activeProvider || ASR_PROVIDER_LOCAL
  localForm.baseUrl = data.local.baseUrl
  localForm.model = data.local.model
  localForm.apiKey = ''
  dashForm.baseUrl = data.dashscope.baseUrl
  dashForm.model = data.dashscope.model
  dashForm.languageHints = data.dashscope.languageHints
  dashForm.apiKey = ''
  timeoutSeconds.value = data.timeoutSeconds ?? ASR_TIMEOUT_DEFAULT_SECONDS
}

/** 配置变更后清空旧的连通性测试结果（避免「改完配置仍显示连通正常」） */
function clearTestResults() {
  for (const key of Object.keys(testResults)) delete testResults[key]
}

async function loadSettings() {
  if (!canWrite.value) return
  loading.value = true
  loadError.value = ''
  try {
    applyView(await api.getAsrSettings())
  } catch (e) {
    loadError.value = adminErrorMessage(e, t('settings.asr.loadFailed'))
  } finally {
    loading.value = false
  }
}

/**
 * 保存并生效：把「选中 provider + 两张卡里未保存的配置」合并成一次 PUT。
 * - 选中 != 生效 → 切换；选中 == 生效 → 仅更新该服务配置。
 * - 未就绪 provider 若已填新 Key，会随请求一起保存并完成切换。
 */
async function saveSettings() {
  if (!canSave.value) return
  const previousActive = view.value?.activeProvider
  saving.value = true
  try {
    const data = await api.updateAsrSettings(
      buildAsrUpdatePayload({
        activeProvider: draftProvider.value,
        timeoutSeconds: timeoutSeconds.value ?? ASR_TIMEOUT_DEFAULT_SECONDS,
        ...(localDirty.value ? { local: localForm } : {}),
        ...(dashDirty.value ? { dashscope: dashForm } : {})
      })
    )
    applyView(data)
    clearTestResults()
    ElMessage.success(
      previousActive && previousActive !== data.activeProvider
        ? t('settings.asr.switch.saved', { provider: providerLabel(data.activeProvider) })
        : t('settings.asr.saved')
    )
  } catch {
    // 请求层已统一提示（含后端「目标 provider 未就绪」原因）
  } finally {
    saving.value = false
  }
}

/** 连通性测试：silent 请求，失败原因展示在卡片内（不弹全局提示） */
async function runTest(provider) {
  const target = provider || activeProvider.value
  if (!target || testing.value) return
  testing.value = target
  delete testResults[target]
  try {
    testResults[target] = await api.testAsr(target)
  } catch (e) {
    testResults[target] = {
      ok: false,
      provider: target,
      model: '',
      latencyMs: null,
      message: adminErrorMessage(e, t('settings.asr.test.failed'))
    }
  } finally {
    testing.value = null
  }
}

onMounted(loadSettings)
</script>

<style scoped>
/* 加载失败降级 */
.asr-fallback {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  color: var(--el-text-color-secondary);
}
.asr-fallback-text {
  min-width: 0;
  flex: 1;
}

.hint {
  margin-top: 8px;
  line-height: 1.7;
}

/* 服务选择（卡片网格标题） */
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
.field-label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 选中 provider 的配置面板 */
.config-panel {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.config-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}
.asr-fields {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  min-width: 0;
}
.field-hint {
  margin-top: 5px;
  line-height: 1.6;
}
.asr-inline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 200px);
  gap: 14px;
}
.full-width {
  width: 100%;
}

/* 说明 */
.asr-notes {
  margin: 16px 0 0;
  padding: 14px 0 0 18px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--el-text-color-regular);
}
@media (max-width: 640px) {
  .asr-inline {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
