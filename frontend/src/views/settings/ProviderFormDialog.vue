<template>
  <!-- 新增 / 编辑供应商（ADMIN） -->
  <el-dialog
    v-model="visible"
    :title="editing ? t('settings.provider.editTitle') : t('settings.provider.create')"
    width="600px"
    top="6vh"
    append-to-body
    class="provider-dialog"
    @closed="resetForm"
  >
    <el-form
      ref="providerFormRef"
      :model="providerForm"
      :rules="providerRules"
      label-width="126px"
      @submit.prevent="submitProvider"
    >
      <el-form-item :label="t('settings.provider.formKey')" prop="key">
        <el-input
          v-if="!editing"
          v-model="providerForm.key"
          :placeholder="t('settings.provider.formKeyPlaceholder')"
          autocomplete="off"
        />
        <el-input v-else :model-value="editing.key" class="mono" disabled />
      </el-form-item>

      <el-form-item :label="t('common.name')" prop="label">
        <el-input
          v-model="providerForm.label"
          maxlength="40"
          :placeholder="t('settings.provider.formLabelPlaceholder')"
        />
      </el-form-item>

      <el-form-item label="Base URL" prop="baseUrl">
        <el-input v-model="providerForm.baseUrl" placeholder="https://api.example.com" />
      </el-form-item>

      <el-form-item label="Chat Path" prop="chatPath">
        <el-input v-model="providerForm.chatPath" placeholder="/v1/chat/completions" />
      </el-form-item>

      <el-form-item :label="t('settings.provider.defaultModel')" prop="defaultModel">
        <el-input
          v-model="providerForm.defaultModel"
          :placeholder="t('settings.provider.formDefaultModelPlaceholder')"
        />
      </el-form-item>

      <el-form-item :label="t('settings.provider.formModels')">
        <el-select
          v-model="providerForm.models"
          multiple
          filterable
          allow-create
          default-first-option
          :placeholder="t('settings.provider.formModelsPlaceholder')"
          style="width: 100%"
        />
        <div class="tiny dim form-hint">{{ t('settings.provider.formModelsHint') }}</div>
      </el-form-item>

      <el-form-item :label="t('settings.provider.pricePer1k')">
        <div class="price-fields">
          <div class="price-field">
            <span class="tiny dim">{{ t('settings.provider.formInputPrice') }}</span>
            <el-input-number
              v-model="providerForm.inputPricePer1k"
              :min="0"
              :precision="6"
              :step="0.0001"
              controls-position="right"
              style="width: 100%"
            />
          </div>
          <div class="price-field">
            <span class="tiny dim">{{ t('settings.provider.formOutputPrice') }}</span>
            <el-input-number
              v-model="providerForm.outputPricePer1k"
              :min="0"
              :precision="6"
              :step="0.0001"
              controls-position="right"
              style="width: 100%"
            />
          </div>
        </div>
        <div class="tiny dim form-hint">
          {{ t('settings.provider.formPriceHint') }}
        </div>
      </el-form-item>

      <el-form-item :label="t('settings.provider.formMaxTokens')">
        <el-input-number
          v-model="providerForm.maxTokens"
          :min="0"
          :step="256"
          :precision="0"
          controls-position="right"
          :placeholder="t('settings.provider.formMaxTokensPlaceholder')"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item :label="t('settings.provider.formJsonMode')">
        <el-switch v-model="providerForm.jsonMode" />
        <span class="tiny dim form-inline-hint">{{ t('settings.provider.formJsonModeHint') }}</span>
      </el-form-item>

      <el-form-item :label="t('settings.provider.formEnabled')">
        <el-switch v-model="providerForm.enabled" />
        <span class="tiny dim form-inline-hint">{{ t('settings.provider.formEnabledHint') }}</span>
      </el-form-item>

      <el-form-item label="API Key">
        <el-input
          v-model="providerForm.apiKey"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="
            editing
              ? t('settings.provider.formApiKeyKeep', {
                  masked: editing.apiKeyMasked || t('settings.state.notConfigured')
                })
              : t('settings.provider.formApiKeyOptional')
          "
        />
        <div class="tiny dim form-hint">{{ t('settings.provider.formApiKeyHint') }}</div>
      </el-form-item>

      <el-alert
        v-if="editing?.builtIn"
        type="info"
        :closable="false"
        show-icon
        :title="t('settings.provider.formBuiltInAlert')"
      />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="providerSaving" @click="submitProvider">
        {{ editing ? t('settings.provider.saveEdit') : t('settings.provider.createSubmit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'

const emit = defineEmits(['saved'])
const { t } = useI18n()

const visible = ref(false)
const editing = ref(null)
const providerSaving = ref(false)
const providerFormRef = ref(null)

function emptyProviderForm() {
  return {
    key: '',
    label: '',
    baseUrl: '',
    chatPath: '/v1/chat/completions',
    defaultModel: '',
    models: [],
    jsonMode: true,
    maxTokens: null,
    inputPricePer1k: 0,
    outputPricePer1k: 0,
    apiKey: '',
    enabled: true
  }
}

const providerForm = reactive(emptyProviderForm())

/** 校验规则：文案随语言切换，用 computed 保证换语言后重新校验时拿到新文案 */
const providerRules = computed(() => ({
  key: [{ validator: validateProviderKey, trigger: 'blur' }],
  label: [{ required: true, message: t('settings.provider.ruleLabelRequired'), trigger: 'blur' }],
  baseUrl: [
    { required: true, message: t('settings.provider.ruleBaseUrlRequired'), trigger: 'blur' },
    {
      pattern: /^https?:\/\/\S+$/i,
      message: t('settings.provider.ruleBaseUrlPattern'),
      trigger: 'blur'
    }
  ],
  defaultModel: [
    {
      required: true,
      message: t('settings.provider.ruleDefaultModelRequired'),
      trigger: 'blur'
    }
  ]
}))

/** 唯一标识只在新建时校验（编辑时 key 不可改，兼容后端已有命名） */
function validateProviderKey(rule, value, callback) {
  if (editing.value) return callback()
  const text = String(value || '').trim()
  if (!text) return callback(new Error(t('settings.provider.ruleKeyRequired')))
  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(text)) {
    return callback(new Error(t('settings.provider.ruleKeyPattern')))
  }
  callback()
}

function resetForm() {
  editing.value = null
  Object.assign(providerForm, emptyProviderForm())
  providerFormRef.value?.clearValidate?.()
}

/** 打开弹窗：row 为 null 表示新建，否则编辑该供应商 */
function open(row) {
  if (!row) {
    resetForm()
  } else {
    editing.value = row
    Object.assign(providerForm, {
      key: row.key || '',
      label: row.label || '',
      baseUrl: row.baseUrl || '',
      chatPath: row.chatPath || '/v1/chat/completions',
      defaultModel: row.defaultModel || '',
      models: [...(row.models || [])],
      jsonMode: row.jsonMode !== false,
      maxTokens: row.maxTokens ?? null,
      inputPricePer1k: row.inputPricePer1k ?? 0,
      outputPricePer1k: row.outputPricePer1k ?? 0,
      apiKey: '',
      enabled: row.enabled !== false
    })
  }
  visible.value = true
}

async function submitProvider() {
  if (!providerFormRef.value) return
  try {
    await providerFormRef.value.validate()
  } catch {
    return
  }
  const current = editing.value
  providerSaving.value = true
  try {
    const payload = {
      label: providerForm.label.trim(),
      baseUrl: providerForm.baseUrl.trim(),
      chatPath: providerForm.chatPath.trim() || undefined,
      defaultModel: providerForm.defaultModel.trim(),
      models: (providerForm.models || []).map((m) => String(m).trim()).filter(Boolean),
      jsonMode: !!providerForm.jsonMode,
      maxTokens:
        providerForm.maxTokens === null || providerForm.maxTokens === undefined
          ? null
          : Number(providerForm.maxTokens),
      inputPricePer1k: Number(providerForm.inputPricePer1k) || 0,
      outputPricePer1k: Number(providerForm.outputPricePer1k) || 0,
      enabled: !!providerForm.enabled
    }
    // 密钥留空表示保持原密钥（编辑）或不配置（新建）
    const apiKey = providerForm.apiKey.trim()
    if (apiKey) payload.apiKey = apiKey

    if (current) {
      await api.updateProvider(current.key, payload)
      ElMessage.success(t('settings.provider.updated', { label: payload.label }))
    } else {
      payload.key = providerForm.key.trim()
      await api.createProvider(payload)
      ElMessage.success(t('settings.provider.created', { label: payload.label }))
    }
    visible.value = false
    // 通知注册表刷新 screen-model 视图与供应商列表（保持原 refreshAll 行为）
    emit('saved')
  } catch {
    // 请求层已统一提示（重复 key / 当前生效不可删等由后端校验）
  } finally {
    providerSaving.value = false
  }
}

// 父组件通过 ref.open(row) 打开：与拆分前 openProviderCreate / openProviderEdit 等价
defineExpose({ open })
</script>

<style scoped>
/* 供应商表单对话框 */
.price-fields {
  display: flex;
  gap: 12px;
  width: 100%;
}
.price-field {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-hint {
  width: 100%;
  margin-top: 4px;
  line-height: 1.6;
}
.form-inline-hint {
  margin-left: 10px;
}
:global(.provider-dialog .el-dialog__body) {
  max-height: 64vh;
  overflow-y: auto;
  padding: 20px;
}
@media (max-width: 760px) {
  .price-fields {
    flex-direction: column;
  }
}
</style>
