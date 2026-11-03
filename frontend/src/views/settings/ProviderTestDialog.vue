<template>
  <!-- 供应商连通性测试（可选模型） -->
  <el-dialog
    v-model="visible"
    :title="t('settings.providerTest.title')"
    width="520px"
    append-to-body
    @closed="reset"
  >
    <div v-if="providerTestTarget" class="test-target">
      <div class="test-target-name">
        {{ providerTestTarget.label || providerTestTarget.key }}
        <span v-if="providerTestTarget.builtIn" class="chip info">{{
          t('settings.provider.builtIn')
        }}</span>
        <span v-if="providerTestTarget.enabled === false" class="chip warn">{{
          t('settings.providerTest.disabledChip')
        }}</span>
      </div>
      <div class="mono tiny dim">
        {{ providerTestTarget.baseUrl }}{{ providerTestTarget.chatPath }}
      </div>
    </div>
    <el-form label-width="86px" @submit.prevent="runProviderTest">
      <el-form-item :label="t('settings.providerTest.model')">
        <el-input
          v-model="providerTestModel"
          clearable
          :placeholder="
            t('settings.providerTest.modelPlaceholder', {
              model: providerTestTarget?.defaultModel || '—'
            })
          "
          @keyup.enter="runProviderTest"
        />
      </el-form-item>
    </el-form>
    <div
      v-if="providerTestResult"
      class="test-result"
      :class="providerTestResult.ok ? 'ok' : 'bad'"
    >
      <el-icon :size="16">
        <component :is="providerTestResult.ok ? CircleCheckFilled : CircleCloseFilled" />
      </el-icon>
      <div class="test-text">
        <div class="test-head">
          <b>{{ providerTestResult.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
          <span v-if="providerTestResult.model" class="mono dim">{{
            providerTestResult.model
          }}</span>
          <span v-if="providerTestResult.latencyMs != null" class="num dim">
            {{ t('settings.test.latency', { ms: providerTestResult.latencyMs }) }}
          </span>
        </div>
        <div class="test-msg">
          {{
            providerTestResult.ok
              ? providerTestResult.reply ||
                providerTestResult.message ||
                t('settings.test.emptyReply')
              : providerTestResult.message || t('settings.providerTest.failHint')
          }}
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">{{ t('common.close') }}</el-button>
      <el-button type="primary" :loading="providerTestRunning" @click="runProviderTest">
        {{ t('settings.providerTest.send') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import api from '../../api'
import { useI18n } from '../../i18n'
import { adminErrorMessage } from './adminErrors'
import { CircleCheckFilled, CircleCloseFilled } from '../../components/icons.js'

const { t } = useI18n()

const visible = ref(false)
const providerTestTarget = ref(null)
const providerTestModel = ref('')
const providerTestRunning = ref(false)
const providerTestResult = ref(null)

/** 打开弹窗并清空上一次的模型 / 结果（与拆分前 openProviderTest 等价） */
function open(row) {
  providerTestTarget.value = row
  providerTestModel.value = ''
  providerTestResult.value = null
  visible.value = true
}

function reset() {
  providerTestTarget.value = null
  providerTestModel.value = ''
  providerTestResult.value = null
}

async function runProviderTest() {
  const target = providerTestTarget.value
  if (!target || providerTestRunning.value) return
  providerTestRunning.value = true
  providerTestResult.value = null
  try {
    // 空模型由后端回退到供应商默认模型
    providerTestResult.value = await api.testProvider(target.key, providerTestModel.value.trim())
  } catch (e) {
    providerTestResult.value = {
      ok: false,
      latencyMs: null,
      model: '',
      providerLabel: '',
      reply: '',
      message: adminErrorMessage(e, t('settings.provider.testFailed'))
    }
  } finally {
    providerTestRunning.value = false
  }
}

// 父组件通过 ref.open(row) 打开：与拆分前 openProviderTest 等价
defineExpose({ open })
</script>

<style scoped>
.test-target {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
}
.test-target-name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
  font-weight: 600;
  color: var(--ink-700);
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
</style>
