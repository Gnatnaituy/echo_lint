<template>
  <div class="page stack">
    <!-- ① 当前生效 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">当前 AI 复筛模型</div>
        <span class="tiny dim">作用于语义复筛与敏感词挖掘</span>
      </div>
      <div class="panel-body">
        <div v-if="view" class="active-card">
          <div class="active-left">
            <div class="active-mark" :class="view.apiKeyConfigured ? 'ok' : 'warn'">
              <el-icon :size="19"><Cpu /></el-icon>
            </div>
            <div class="active-info">
              <div class="active-line">
                <span class="active-provider">{{ view.providerLabel }}</span>
                <span class="active-sep">·</span>
                <span class="active-model mono">{{ view.model }}</span>
                <span class="chip" :class="view.source === 'DATABASE' ? 'brand' : 'info'">
                  {{ view.source === 'DATABASE' ? '界面切换' : '配置默认' }}
                </span>
              </div>
              <div class="active-status">
                <span class="dot" :style="{ background: view.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"></span>
                <span :class="view.apiKeyConfigured ? 'dim' : 'warn-text'">
                  {{ view.apiKeyConfigured
                    ? 'API Key 已配置，可正常调用'
                    : 'API Key 未配置：DFA 命中的录音会降级转人工复检，不会漏审' }}
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
            测试连通性
          </el-button>
        </div>
        <div v-else class="empty-block">加载中…</div>

        <div v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'bad'">
          <el-icon :size="16">
            <component :is="testResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
          </el-icon>
          <div class="test-text">
            <div class="test-head">
              <b>{{ testResult.ok ? '连通正常' : '连通失败' }}</b>
              <span class="mono dim">{{ testResult.providerLabel }} · {{ testResult.model }}</span>
              <span v-if="testResult.ok" class="num dim">耗时 {{ testResult.latencyMs }} ms</span>
            </div>
            <div class="test-msg">{{ testResult.ok ? (testResult.reply || '（无返回内容）') : testResult.message }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- ② 切换 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">切换模型</div>
        <span class="tiny dim">保存后立即生效，无需重启；选择会持久化保存</span>
      </div>
      <div class="panel-body">
        <template v-if="canWrite">
        <div class="provider-grid">
          <button
            v-for="p in view?.providers || []"
            :key="p.id"
            type="button"
            class="provider-card"
            :class="{ on: p.id === draft.providerId }"
            @click="pickProvider(p)"
          >
            <div class="pc-head">
              <span class="pc-name">{{ p.label }}</span>
              <span v-if="p.active" class="chip brand">当前</span>
            </div>
            <div class="pc-url mono">{{ p.baseUrl }}</div>
            <div class="pc-foot">
              <span class="dot" :style="{ background: p.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"></span>
              <span class="tiny">
                {{ p.apiKeyConfigured ? 'Key 已配置' : (p.apiKeyEnv ? '缺少 ' + p.apiKeyEnv : 'Key 未配置') }}
              </span>
            </div>
          </button>
        </div>

        <div class="switch-row">
          <div class="field">
            <label class="field-label">模型</label>
            <el-select
              v-model="draft.model"
              class="model-select"
              filterable
              allow-create
              default-first-option
              placeholder="选择或直接输入模型名"
            >
              <el-option v-for="m in currentProvider?.models || []" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="switch-actions">
            <el-button :loading="testing === 'draft'" :disabled="!draft.providerId" @click="testDraft">
              测试
            </el-button>
            <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">
              保存并生效
            </el-button>
          </div>
        </div>
        <div class="hint tiny dim">
          可下拉选择，也可直接输入候选之外的新模型名（供应商上新模型时无需改配置重启）。
          切换立即影响之后的所有复筛与词挖掘，已有结论的历史录音不会被重新判定。
        </div>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="只读角色可查看当前生效模型；切换模型与连通性测试仅管理员可用。"
        />
      </div>
    </section>

    <!-- ③ 生效范围 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">生效范围与密钥配置</div>
      </div>
      <div class="panel-body">
        <ul class="scope-list">
          <li v-for="item in scope" :key="item.name" class="scope-item">
            <el-icon :size="15" :class="item.follow ? 'scope-yes' : 'scope-no'">
              <component :is="item.follow ? 'CircleCheckFilled' : 'RemoveFilled'" />
            </el-icon>
            <span class="scope-name">{{ item.name }}</span>
            <span class="tiny dim">{{ item.note }}</span>
          </li>
        </ul>

        <div class="code-block mono">
          <div class="code-title">在 .env 中补充密钥（docker compose 自动读取）</div>
          <pre># DeepSeek 密钥（切换前必须先配置）
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# 可选：让 DeepSeek 成为首次启动的默认模型
# APP_SCREEN_PROVIDER=deepseek
# DEEPSEEK_SCREEN_MODEL=deepseek-flash</pre>
        </div>
      </div>
    </section>

    <!-- ④ 抽样与通知（ADMIN）：合规抽样比例 + Webhook 告警 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">抽样与通知</div>
        <div class="notify-head">
          <span class="tiny dim">合规抽样与 Webhook 告警，仅管理员可查看</span>
          <el-button size="small" :loading="notifyLoading" @click="loadNotifications">刷新</el-button>
        </div>
      </div>
      <div v-loading="notifyLoading" class="panel-body">
        <!-- 接口未上线 / 无权限（403、404）时降级为可重试的提示，不影响页面其他设置 -->
        <div v-if="notifyError" class="notify-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="notify-fallback-text">
            <div class="strong">通知设置暂不可用</div>
            <div class="tiny dim">{{ notifyError }}</div>
          </div>
          <el-button size="small" @click="loadNotifications">重试</el-button>
        </div>

        <template v-else-if="notify">
          <div class="notify-grid">
            <div class="notify-item">
              <div class="notify-label">DFA 未命中抽样比例</div>
              <div class="notify-value num">{{ samplingRateText }}</div>
              <div class="notify-sub tiny dim">{{ samplingRateNote }}</div>
            </div>

            <div class="notify-item">
              <div class="notify-label">Webhook 通知</div>
              <div class="notify-value">
                <span class="chip" :class="webhookState.type">{{ webhookState.label }}</span>
              </div>
              <div class="notify-sub tiny dim">{{ webhookState.note }}</div>
            </div>

            <div class="notify-item">
              <div class="notify-label">待复核积压阈值</div>
              <div class="notify-value num">{{ backlogThresholdText }}</div>
              <div class="notify-sub tiny dim">待人工复检数超过阈值时每日告警（Asia/Shanghai）</div>
            </div>
          </div>

          <div class="notify-events">
            <span class="notify-label">通知事件</span>
            <div class="event-chips">
              <span
                v-for="event in notify.events"
                :key="event"
                class="chip"
                :class="notifyEventMeta(event).type"
              >{{ notifyEventMeta(event).label }}</span>
              <span v-if="!notify.events.length" class="tiny dim">接口未返回事件列表（后端可能为旧版本）</span>
            </div>
          </div>

          <div class="notify-test">
            <el-button type="primary" :loading="notifyTesting" :disabled="!canTestWebhook" @click="testWebhook">
              <el-icon v-if="!notifyTesting" class="el-icon--left"><Connection /></el-icon>
              发送测试 Webhook
            </el-button>
            <span class="tiny dim">{{ testHint }}</span>
          </div>

          <div v-if="notifyTestResult" class="test-result" :class="notifyTestResult.ok ? 'ok' : 'bad'">
            <el-icon :size="16">
              <component :is="notifyTestResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
            <div class="test-text">
              <div class="test-head">
                <b>{{ notifyTestResult.ok ? '测试通知发送成功' : '测试通知发送失败' }}</b>
                <span v-if="notifyTestResult.latencyMs != null" class="num dim">
                  耗时 {{ notifyTestResult.latencyMs }} ms
                </span>
              </div>
              <div class="test-msg">
                {{ notifyTestResult.message ||
                  (notifyTestResult.ok
                    ? 'Webhook 已接收测试事件'
                    : '请检查 Webhook 地址、签名密钥与网络连通性') }}
              </div>
            </div>
          </div>

          <div class="hint tiny dim">
            抽样比例、Webhook 地址 / 密钥 / 超时、积压阈值均由后端环境变量配置
            （APP_SAMPLE_DFA_MISS_RATE、APP_NOTIFY_*），修改后需重启后端。
            通知只发送到后端配置的地址，前端不接触 URL 与签名密钥。
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import auth from '../auth'
import { notifyEventMeta } from '../constants'
import { formatSamplingRate } from '../utils/notifications'

// 系统设置写操作仅 ADMIN：非管理员只读查看，后端仍会独立校验
const canWrite = computed(() => auth.hasRole('ADMIN'))

const view = ref(null)
const draft = reactive({ providerId: '', model: '' })
const saving = ref(false)
/** 正在测试的目标：'active' | 'draft' | null */
const testing = ref(null)
const testResult = ref(null)

// 抽样与通知（仅 ADMIN）：GET /api/settings/notifications 为 ADMIN 接口，
// 后端未上线（404）或无权限（403）时降级为卡片内提示，不弹全局错误。
const notify = ref(null)
const notifyLoading = ref(false)
const notifyError = ref('')
const notifyTesting = ref(false)
const notifyTestResult = ref(null)

const currentProvider = computed(
  () => (view.value?.providers || []).find((p) => p.id === draft.providerId) || null
)

const dirty = computed(
  () => !!view.value && (draft.providerId !== view.value.providerId || draft.model !== view.value.model)
)

const scope = [
  { name: 'AI 语义复筛', follow: true, note: 'DFA 命中后的语境判断，结论决定是否转人工' },
  { name: '敏感词挖掘', follow: true, note: '确认违规后由 AI 提炼新词入库（默认停用待审核）' },
  { name: '语音转写（Whisper）', follow: false, note: '固定走 OpenAI；chat 类供应商不提供转写能力' },
  { name: 'DFA 初筛', follow: false, note: '本地 Aho-Corasick 匹配，不调用外部模型' }
]

async function load() {
  try {
    const data = await api.screenModel()
    view.value = data
    draft.providerId = data.providerId
    draft.model = data.model
  } catch {
    // 请求层已统一提示
  }
}

function pickProvider(p) {
  draft.providerId = p.id
  draft.model = p.defaultModel || p.models?.[0] || ''
}

async function save() {
  saving.value = true
  testResult.value = null
  try {
    const data = await api.updateScreenModel({ providerId: draft.providerId, model: draft.model })
    view.value = data
    draft.providerId = data.providerId
    draft.model = data.model
    ElMessage.success(`已切换到 ${data.providerLabel} · ${data.model}`)
  } catch {
    // 请求层已统一提示
  } finally {
    saving.value = false
  }
}

async function runTest(target, payload) {
  testing.value = target
  testResult.value = null
  try {
    testResult.value = await api.testScreenModel(payload)
  } catch {
    // 请求层已统一提示
  } finally {
    testing.value = null
  }
}

/** 测「当前生效」的模型 */
function testActive() {
  if (!view.value) return
  runTest('active', { providerId: view.value.providerId, model: view.value.model })
}

/** 测「待保存」的选择，便于先验证再切换 */
function testDraft() {
  if (!draft.providerId) return
  runTest('draft', { providerId: draft.providerId, model: draft.model })
}

/* ---------- 抽样与通知（ADMIN） ---------- */

const samplingRateText = computed(() => formatSamplingRate(notify.value?.samplingRate))

const samplingRateNote = computed(() =>
  notify.value?.samplingRate != null
    ? 'DFA 无命中的录音按该比例进入 AI 语义复筛 / 人工复检'
    : '后端未返回当前值：由环境变量 APP_SAMPLE_DFA_MISS_RATE 配置（0~1，重启生效）'
)

/** Webhook 状态：接口字段缺失时显示「未知」，不臆造启用状态 */
const webhookState = computed(() => {
  const data = notify.value
  if (!data) return { label: '—', type: 'info', note: '' }
  if (data.enabled === false) {
    return { label: '未启用', type: 'info', note: 'APP_NOTIFY_ENABLED 未开启，不会发送任何通知' }
  }
  if (data.enabled === true) {
    if (data.urlConfigured === false) {
      return { label: '待配置 URL', type: 'warn', note: '已启用但 Webhook URL 未配置，事件不会送达' }
    }
    if (data.urlConfigured === true) {
      return { label: '已启用', type: 'ok', note: '地址与签名密钥由后端环境变量配置，前端只读' }
    }
    return { label: '已启用', type: 'ok', note: '接口未返回 URL 配置状态' }
  }
  if (data.urlConfigured === true) {
    return { label: '已配置', type: 'ok', note: '接口未返回启用状态' }
  }
  return { label: '未知', type: 'info', note: '接口未返回通知状态，可能是后端版本较旧' }
})

const backlogThresholdText = computed(() => {
  const threshold = notify.value?.backlogThreshold
  return threshold === null || threshold === undefined ? '—' : `${threshold} 条`
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
  if (data.enabled === false) return '通知未启用：需在后端开启 APP_NOTIFY_ENABLED'
  if (data.urlConfigured === false) return 'Webhook URL 未配置：需在后端配置 APP_NOTIFY_WEBHOOK_URL'
  return '发送一条 NOTIFY_TEST 事件，验证地址连通性与签名，结果会写入操作审计'
})

/** 接口错误文案：区分 403/404，避免把后端未上线误报成普通失败 */
function notifyErrorMessage(error, fallback) {
  const status = error?.status
  if (status === 403) return '当前账号无权限执行该操作（需要 ADMIN）'
  if (status === 404) return '后端尚未提供该接口（HTTP 404），请确认后端版本'
  return error?.message || fallback
}

async function loadNotifications() {
  if (!canWrite.value) return
  notifyLoading.value = true
  notifyError.value = ''
  try {
    notify.value = await api.notificationSettings()
  } catch (e) {
    notify.value = null
    notifyError.value = notifyErrorMessage(e, '通知设置加载失败，请稍后重试')
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
      message: notifyErrorMessage(e, '测试通知发送失败，请检查 Webhook 配置与网络')
    }
  } finally {
    notifyTesting.value = false
  }
}

onMounted(() => {
  load()
  // 通知设置是 ADMIN 接口：非管理员不请求、不渲染该卡片
  if (canWrite.value) loadNotifications()
})
</script>

<style scoped>
/* 当前生效卡片 */
.active-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 17px;
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
.active-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 5px;
  font-size: 12px;
}
.warn-text {
  color: #b06a06;
}

/* 测试结果 */
.test-result {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding: 11px 14px;
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

/* 供应商卡片 */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: 12px;
}
.provider-card {
  display: block;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fff;
  cursor: pointer;
  transition: all 0.18s ease;
  font: inherit;
}
.provider-card:hover {
  border-color: var(--brand-300);
  transform: translateY(-1px);
  box-shadow: var(--sh-xs);
}
.provider-card.on {
  border-color: var(--brand-500);
  background: var(--brand-50);
  box-shadow: 0 0 0 3px rgba(59, 110, 246, 0.1);
}
.pc-head {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 切换行 */
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
  margin-top: 11px;
  line-height: 1.75;
}

/* 生效范围 */
.scope-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.scope-item {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
}
.scope-yes {
  color: var(--ok);
}
.scope-no {
  color: var(--ink-400);
}
.scope-name {
  min-width: 132px;
  color: var(--ink-700);
  font-weight: 500;
}

.code-block {
  margin-top: 15px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  padding: 12px 14px;
}
.code-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.code-block pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-600);
  white-space: pre-wrap;
  word-break: break-all;
}

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
</style>
