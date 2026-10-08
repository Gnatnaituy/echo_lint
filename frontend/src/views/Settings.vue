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
          <el-button :loading="testing === 'active'" :disabled="testing === 'draft'" @click="testActive">
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
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const view = ref(null)
const draft = reactive({ providerId: '', model: '' })
const saving = ref(false)
/** 正在测试的目标：'active' | 'draft' | null */
const testing = ref(null)
const testResult = ref(null)

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

onMounted(load)
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
</style>
