<template>
  <!-- ⑦ 抽样与通知（ADMIN）：合规抽样比例 + Webhook 告警 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.notify.title') }}</div>
      <div class="notify-head">
        <span class="tiny dim">{{ t('settings.notify.subtitle') }}</span>
        <el-button size="small" :loading="notifyLoading" @click="loadNotifications">{{
          t('common.refresh')
        }}</el-button>
      </div>
    </div>
    <div v-loading="notifyLoading" class="panel-body">
      <!-- 接口未上线 / 无权限（403、404）时降级为可重试的提示，不影响页面其他设置 -->
      <div v-if="notifyError" class="notify-fallback">
        <el-icon :size="16"><WarningFilled /></el-icon>
        <div class="notify-fallback-text">
          <div class="strong">{{ t('settings.notify.unavailable') }}</div>
          <div class="tiny dim">{{ notifyError }}</div>
        </div>
        <el-button size="small" @click="loadNotifications">{{ t('common.retry') }}</el-button>
      </div>

      <template v-else-if="notify">
        <div class="notify-grid">
          <div class="notify-item">
            <div class="notify-label">{{ t('settings.notify.sampleRate') }}</div>
            <div class="notify-value num">{{ samplingRateText }}</div>
            <div class="notify-sub tiny dim">{{ samplingRateNote }}</div>
          </div>

          <div class="notify-item">
            <div class="notify-label">{{ t('settings.notify.webhook') }}</div>
            <div class="notify-value">
              <span class="chip" :class="webhookState.type">{{ webhookState.label }}</span>
            </div>
            <div class="notify-sub tiny dim">{{ webhookState.note }}</div>
          </div>

          <div class="notify-item">
            <div class="notify-label">{{ t('settings.notify.backlog') }}</div>
            <div class="notify-value num">{{ backlogThresholdText }}</div>
            <div class="notify-sub tiny dim">{{ t('settings.notify.backlogNote') }}</div>
          </div>
        </div>

        <div class="notify-events">
          <span class="notify-label">{{ t('settings.notify.events') }}</span>
          <div class="event-chips">
            <span
              v-for="event in notify.events"
              :key="event"
              class="chip"
              :class="notifyEventMeta(event).type"
              >{{ notifyEventMeta(event).label }}</span
            >
            <span v-if="!notify.events.length" class="tiny dim">{{
              t('settings.notify.eventsEmpty')
            }}</span>
          </div>
        </div>

        <div class="notify-test">
          <el-button
            type="primary"
            :loading="notifyTesting"
            :disabled="!canTestWebhook"
            @click="testWebhook"
          >
            <el-icon v-if="!notifyTesting" class="el-icon--left"><Connection /></el-icon>
            {{ t('settings.notify.sendTest') }}
          </el-button>
          <span class="tiny dim">{{ testHint }}</span>
        </div>

        <div
          v-if="notifyTestResult"
          class="test-result"
          :class="notifyTestResult.ok ? 'ok' : 'bad'"
        >
          <el-icon :size="16">
            <component :is="notifyTestResult.ok ? CircleCheckFilled : CircleCloseFilled" />
          </el-icon>
          <div class="test-text">
            <div class="test-head">
              <b>{{
                notifyTestResult.ok ? t('settings.notify.testOk') : t('settings.notify.testFail')
              }}</b>
              <span v-if="notifyTestResult.latencyMs != null" class="num dim">
                {{ t('settings.test.latency', { ms: notifyTestResult.latencyMs }) }}
              </span>
            </div>
            <div class="test-msg">
              {{
                notifyTestResult.message ||
                (notifyTestResult.ok
                  ? t('settings.notify.testSuccessMsg')
                  : t('settings.notify.testFailMsg'))
              }}
            </div>
          </div>
        </div>

        <div class="hint tiny dim">
          {{ t('settings.notify.hintA') }}
          {{ t('settings.notify.hintB') }}
          {{ t('settings.notify.hintC') }}
        </div>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../../api'
import { useI18n } from '../../i18n'
import { notifyEventMeta } from '../../constants'
import { formatSamplingRate } from '../../utils/notifications'
import { adminErrorMessage } from './adminErrors'
import { useSettingsContext } from './context'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Connection,
  WarningFilled
} from '../../components/icons.js'

const { t } = useI18n()
const { canWrite } = useSettingsContext()

// 抽样与通知（仅 ADMIN）：GET /api/settings/notifications 为 ADMIN 接口，
// 后端未上线（404）或无权限（403）时降级为卡片内提示，不弹全局错误。
const notify = ref(null)
const notifyLoading = ref(false)
const notifyError = ref('')
const notifyTesting = ref(false)
const notifyTestResult = ref(null)

const samplingRateText = computed(() => formatSamplingRate(notify.value?.samplingRate))

const samplingRateNote = computed(() =>
  notify.value?.samplingRate != null
    ? t('settings.notify.sampleRateNote')
    : t('settings.notify.sampleRateMissing')
)

/** Webhook 状态：接口字段缺失时显示「未知」，不臆造启用状态 */
const webhookState = computed(() => {
  const data = notify.value
  if (!data) return { label: '—', type: 'info', note: '' }
  if (data.enabled === false) {
    return {
      label: t('settings.state.disabled'),
      type: 'info',
      note: t('settings.notify.disabledNote')
    }
  }
  if (data.enabled === true) {
    if (data.urlConfigured === false) {
      return {
        label: t('settings.state.urlMissing'),
        type: 'warn',
        note: t('settings.notify.urlMissingNote')
      }
    }
    if (data.urlConfigured === true) {
      return {
        label: t('settings.state.enabled'),
        type: 'ok',
        note: t('settings.notify.enabledNote')
      }
    }
    return {
      label: t('settings.state.enabled'),
      type: 'ok',
      note: t('settings.notify.urlUnknownNote')
    }
  }
  if (data.urlConfigured === true) {
    return {
      label: t('settings.state.configured'),
      type: 'ok',
      note: t('settings.notify.enabledUnknownNote')
    }
  }
  return {
    label: t('common.unknown'),
    type: 'info',
    note: t('settings.notify.unknownNote')
  }
})

const backlogThresholdText = computed(() => {
  const threshold = notify.value?.backlogThreshold
  return threshold === null || threshold === undefined
    ? '—'
    : t('settings.notify.backlogValue', { count: threshold })
})

/** 仅当能确认未启用 / URL 未配置时禁用测试，字段缺失时仍允许尝试（后端为准） */
const canTestWebhook = computed(() => {
  const data = notify.value
  if (!data || notifyTesting.value) return false
  return data.enabled !== false && data.urlConfigured !== false
})

const testHint = computed(() => {
  const data = notify.value
  if (!data) return ''
  if (data.enabled === false) return t('settings.notify.hintDisabled')
  if (data.urlConfigured === false) return t('settings.notify.hintUrlMissing')
  return t('settings.notify.hintTest')
})

async function loadNotifications() {
  if (!canWrite.value) return
  notifyLoading.value = true
  notifyError.value = ''
  try {
    notify.value = await api.notificationSettings()
  } catch (e) {
    notify.value = null
    notifyError.value = adminErrorMessage(e, t('settings.notify.loadFailed'))
  } finally {
    notifyLoading.value = false
  }
}

async function testWebhook() {
  if (!canTestWebhook.value) return
  notifyTesting.value = true
  notifyTestResult.value = null
  try {
    notifyTestResult.value = await api.testNotification()
  } catch (e) {
    notifyTestResult.value = {
      ok: false,
      latencyMs: null,
      message: adminErrorMessage(e, t('settings.notify.testFailedMsg'))
    }
  } finally {
    notifyTesting.value = false
  }
}

onMounted(loadNotifications)
</script>

<style scoped>
/* 抽样与通知 */
.notify-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.notify-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.notify-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.notify-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.notify-value {
  margin-top: 5px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.notify-sub {
  margin-top: 6px;
  line-height: 1.65;
}
.notify-events {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 14px;
}
.notify-events .notify-label {
  flex: none;
}
.event-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.notify-test {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.notify-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.notify-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
@media (max-width: 1100px) {
  .notify-grid {
    grid-template-columns: minmax(0, 1fr);
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

.hint {
  margin-top: 12px;
  line-height: 1.7;
}
</style>
