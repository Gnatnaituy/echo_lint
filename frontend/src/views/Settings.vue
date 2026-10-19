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
            :class="{ on: p.id === draft.providerId, off: isProviderDisabled(p.id) }"
            :disabled="isProviderDisabled(p.id)"
            :title="isProviderDisabled(p.id) ? '该供应商已在「AI 供应商」中停用，启用后才能切换' : p.baseUrl"
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

    <!-- ③ AI 供应商（ADMIN）：注册表管理，仍可切换当前生效供应商 / 模型 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">AI 供应商</div>
        <div class="provider-head">
          <span class="tiny dim">内置供应商由后端配置首次启动落库；密钥加密存储，仅显示掩码</span>
          <el-button size="small" :loading="providersLoading" @click="loadProviders">刷新</el-button>
          <el-button size="small" type="primary" @click="openProviderCreate">
            <el-icon class="el-icon--left"><Plus /></el-icon>
            新增供应商
          </el-button>
        </div>
      </div>
      <div v-loading="providersLoading" class="panel-body">
        <!-- 接口未上线 / 无权限（403、404）时降级为可重试提示，不影响上方切换模型 -->
        <div v-if="providersError" class="provider-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="provider-fallback-text">
            <div class="strong">供应商列表暂不可用</div>
            <div class="tiny dim">{{ providersError }}</div>
          </div>
          <el-button size="small" @click="loadProviders">重试</el-button>
        </div>

        <template v-else>
          <el-table :data="providers" size="small">
            <el-table-column label="供应商" min-width="190">
              <template #default="{ row }">
                <div class="provider-cell">
                  <div class="provider-name">
                    <span class="strong">{{ row.label || row.key || '未命名' }}</span>
                    <span v-if="providerIsActive(row)" class="chip brand">当前</span>
                    <span v-if="row.builtIn" class="chip info">内置</span>
                  </div>
                  <div class="mono tiny dim">{{ row.key || '—' }}</div>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="接入地址" min-width="230">
              <template #default="{ row }">
                <div class="provider-cell">
                  <div class="mono tiny ellipsis" :title="row.baseUrl">{{ row.baseUrl || '—' }}</div>
                  <div class="mono tiny dim ellipsis" :title="row.chatPath">
                    {{ row.chatPath || '默认 chatPath' }}
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="默认模型" min-width="150">
              <template #default="{ row }">
                <div class="mono tiny">{{ row.defaultModel || '—' }}</div>
                <div class="tiny dim">{{ row.models.length }} 个候选模型</div>
              </template>
            </el-table-column>

            <el-table-column label="价格 / 1K tokens" width="164" align="right">
              <template #default="{ row }">
                <div class="price-cell">
                  <span><span class="tiny dim">入</span> <span class="mono">{{ formatPricePer1k(row.inputPricePer1k) }}</span></span>
                  <span><span class="tiny dim">出</span> <span class="mono">{{ formatPricePer1k(row.outputPricePer1k) }}</span></span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="密钥" width="128">
              <template #default="{ row }">
                <span class="chip mono" :class="providerKeyChip(row).type">{{ providerKeyChip(row).label }}</span>
              </template>
            </el-table-column>

            <el-table-column label="状态" width="86" align="center">
              <template #default="{ row }">
                <span class="chip" :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'">
                  {{ row.enabled === false ? '停用' : row.enabled === true ? '启用' : '未知' }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="178" align="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openProviderTest(row)">测试</el-button>
                <el-button size="small" link type="primary" @click="openProviderEdit(row)">编辑</el-button>
                <el-tooltip
                  v-if="!row.builtIn"
                  :disabled="!providerIsActive(row)"
                  content="当前生效供应商不可删除，请先切换模型"
                  placement="top"
                >
                  <span class="delete-wrap">
                    <el-popconfirm
                      title="删除后该供应商配置与密钥将被移除（不可恢复），确认删除？"
                      width="270"
                      @confirm="removeProvider(row)"
                    >
                      <template #reference>
                        <el-button size="small" link type="danger" :disabled="providerIsActive(row)">
                          删除
                        </el-button>
                      </template>
                    </el-popconfirm>
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>

            <template #empty>
              <div class="empty-block">
                <el-icon :size="34" class="empty-icon"><Cpu /></el-icon>
                <div class="empty-title">暂无供应商</div>
                <div class="tiny dim">
                  内置供应商会在后端首次启动时自动落库；也可点击右上角「新增供应商」接入自定义网关
                </div>
              </div>
            </template>
          </el-table>

          <div class="hint tiny dim">
            内置供应商可编辑接入信息、价格与密钥，但不可删除；自定义供应商可增删。
            停用或删除不会影响历史录音结论与已产生的调用留痕。
          </div>
        </template>
      </div>
    </section>

    <!-- ④ 生效范围 -->
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

    <!-- ⑤ 抽样与通知（ADMIN）：合规抽样比例 + Webhook 告警 -->
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

    <!-- 新增 / 编辑供应商（ADMIN） -->
    <el-dialog
      v-model="providerDialogVisible"
      :title="providerEditing ? '编辑供应商' : '新增供应商'"
      width="600px"
      top="6vh"
      append-to-body
      class="provider-dialog"
      @closed="resetProviderForm"
    >
      <el-form
        ref="providerFormRef"
        :model="providerForm"
        :rules="providerRules"
        label-width="126px"
        @submit.prevent="submitProvider"
      >
        <el-form-item label="唯一标识" prop="key">
          <el-input
            v-if="!providerEditing"
            v-model="providerForm.key"
            placeholder="如 my-gateway（小写字母 / 数字 / . _ -）"
            autocomplete="off"
          />
          <el-input v-else :model-value="providerEditing.key" class="mono" disabled />
        </el-form-item>

        <el-form-item label="名称" prop="label">
          <el-input v-model="providerForm.label" maxlength="40" placeholder="展示名称，如 自建网关" />
        </el-form-item>

        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="providerForm.baseUrl" placeholder="https://api.example.com" />
        </el-form-item>

        <el-form-item label="Chat Path" prop="chatPath">
          <el-input v-model="providerForm.chatPath" placeholder="/v1/chat/completions" />
        </el-form-item>

        <el-form-item label="默认模型" prop="defaultModel">
          <el-input v-model="providerForm.defaultModel" placeholder="如 gpt-4o-mini" />
        </el-form-item>

        <el-form-item label="模型列表">
          <el-select
            v-model="providerForm.models"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入模型名后回车添加"
            style="width: 100%"
          />
          <div class="tiny dim form-hint">用于切换模型下拉与连通性测试；默认模型会自动包含。</div>
        </el-form-item>

        <el-form-item label="价格 / 1K">
          <div class="price-fields">
            <div class="price-field">
              <span class="tiny dim">输入</span>
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
              <span class="tiny dim">输出</span>
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
            单位 USD / 1K tokens，调用时按此单价快照计算成本；0 表示不参与费用统计。
          </div>
        </el-form-item>

        <el-form-item label="最大输出 Tokens">
          <el-input-number
            v-model="providerForm.maxTokens"
            :min="0"
            :step="256"
            :precision="0"
            controls-position="right"
            placeholder="留空使用供应商默认"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="JSON 模式">
          <el-switch v-model="providerForm.jsonMode" />
          <span class="tiny dim form-inline-hint">要求模型返回 JSON 对象</span>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="providerForm.enabled" />
          <span class="tiny dim form-inline-hint">停用后不可切换为当前模型</span>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="providerEditing
              ? `留空不修改（当前：${providerEditing.apiKeyMasked || '未配置'}）`
              : '可留空，稍后补充'"
          />
          <div class="tiny dim form-hint">仅提交给后端加密存储，接口与前端永远只回掩码。</div>
        </el-form-item>

        <el-alert
          v-if="providerEditing?.builtIn"
          type="info"
          :closable="false"
          show-icon
          title="内置供应商可编辑接入信息、价格与密钥，但不可删除；密钥留空表示保持不变。"
        />
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="providerSaving" @click="submitProvider">
          {{ providerEditing ? '保存修改' : '创建供应商' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 供应商连通性测试（可选模型） -->
    <el-dialog
      v-model="providerTestVisible"
      title="测试供应商连通性"
      width="520px"
      append-to-body
      @closed="resetProviderTest"
    >
      <div v-if="providerTestTarget" class="test-target">
        <div class="test-target-name">
          {{ providerTestTarget.label || providerTestTarget.key }}
          <span v-if="providerTestTarget.builtIn" class="chip info">内置</span>
          <span v-if="providerTestTarget.enabled === false" class="chip warn">已停用</span>
        </div>
        <div class="mono tiny dim">
          {{ providerTestTarget.baseUrl }}{{ providerTestTarget.chatPath }}
        </div>
      </div>
      <el-form label-width="86px" @submit.prevent="runProviderTest">
        <el-form-item label="测试模型">
          <el-input
            v-model="providerTestModel"
            clearable
            :placeholder="`留空使用默认模型：${providerTestTarget?.defaultModel || '—'}`"
            @keyup.enter="runProviderTest"
          />
        </el-form-item>
      </el-form>
      <div v-if="providerTestResult" class="test-result" :class="providerTestResult.ok ? 'ok' : 'bad'">
        <el-icon :size="16">
          <component :is="providerTestResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
        </el-icon>
        <div class="test-text">
          <div class="test-head">
            <b>{{ providerTestResult.ok ? '连通正常' : '连通失败' }}</b>
            <span v-if="providerTestResult.model" class="mono dim">{{ providerTestResult.model }}</span>
            <span v-if="providerTestResult.latencyMs != null" class="num dim">
              耗时 {{ providerTestResult.latencyMs }} ms
            </span>
          </div>
          <div class="test-msg">
            {{ providerTestResult.ok
              ? (providerTestResult.reply || providerTestResult.message || '（无返回内容）')
              : (providerTestResult.message || '请检查 Base URL / 密钥 / 模型名') }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="providerTestVisible = false">关闭</el-button>
        <el-button type="primary" :loading="providerTestRunning" @click="runProviderTest">
          发送测试请求
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import auth from '../auth'
import { notifyEventMeta } from '../constants'
import { formatSamplingRate } from '../utils/notifications'
import { formatPricePer1k, isProviderEnabled } from '../utils/providers'

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

// AI 供应商注册表（仅 ADMIN）：内置项由后端配置落库，自定义项可增删改
const providers = ref([])
const providersLoading = ref(false)
const providersError = ref('')
const providerDialogVisible = ref(false)
const providerEditing = ref(null)
const providerSaving = ref(false)
const providerFormRef = ref(null)
const providerForm = reactive(emptyProviderForm())
const providerTestVisible = ref(false)
const providerTestTarget = ref(null)
const providerTestModel = ref('')
const providerTestRunning = ref(false)
const providerTestResult = ref(null)

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

/** 接口错误文案：区分 403/404，避免把后端未上线误报成普通失败（通知 / 供应商共用） */
function adminErrorMessage(error, fallback) {
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
    notifyError.value = adminErrorMessage(e, '通知设置加载失败，请稍后重试')
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
      message: adminErrorMessage(e, '测试通知发送失败，请检查 Webhook 配置与网络')
    }
  } finally {
    notifyTesting.value = false
  }
}

/* ---------- AI 供应商（ADMIN） ---------- */

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

const providerRules = {
  key: [{ validator: validateProviderKey, trigger: 'blur' }],
  label: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\/\S+$/i, message: '需为 http(s):// 开头的地址', trigger: 'blur' }
  ],
  defaultModel: [{ required: true, message: '请输入默认模型', trigger: 'blur' }]
}

/** 唯一标识只在新建时校验（编辑时 key 不可改，兼容后端已有命名） */
function validateProviderKey(rule, value, callback) {
  if (providerEditing.value) return callback()
  const text = String(value || '').trim()
  if (!text) return callback(new Error('请输入唯一标识'))
  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(text)) {
    return callback(new Error('1-64 位小写字母 / 数字 / . _ -，以字母或数字开头'))
  }
  callback()
}

const currentProviderKey = computed(() => String(view.value?.providerId || ''))

/** 该供应商是否是「当前生效」的供应商 */
function providerIsActive(row) {
  if (!currentProviderKey.value || !row?.key) return false
  return row.key.toLowerCase() === currentProviderKey.value.toLowerCase()
}

/** 切换模型卡片：停用的供应商不可选（列表接口不可用时按可用处理，保持原行为） */
function isProviderDisabled(key) {
  if (!key) return false
  const found = providers.value.find(
    (p) => String(p.key || '').toLowerCase() === String(key).toLowerCase()
  )
  return !!found && !isProviderEnabled(found)
}

/** 密钥状态：只展示后端掩码，不接触明文 */
function providerKeyChip(row) {
  const masked = row?.apiKeyMasked || ''
  if (row?.apiKeyConfigured === true) return { label: masked || '已配置', type: 'ok' }
  if (row?.apiKeyConfigured === false) return { label: '未配置', type: 'warn' }
  return { label: masked || '未知', type: 'info' }
}

async function loadProviders() {
  if (!canWrite.value) return
  providersLoading.value = true
  providersError.value = ''
  try {
    providers.value = await api.listProviders()
  } catch (e) {
    providers.value = []
    providersError.value = adminErrorMessage(e, '供应商列表加载失败，请稍后重试')
  } finally {
    providersLoading.value = false
  }
}

/** 供应商增删改 / 测试后同时刷新注册表与「当前生效」视图，避免切换区显示旧数据 */
async function refreshAll() {
  await Promise.all([load(), loadProviders()])
}

function resetProviderForm() {
  providerEditing.value = null
  Object.assign(providerForm, emptyProviderForm())
  providerFormRef.value?.clearValidate?.()
}

function openProviderCreate() {
  resetProviderForm()
  providerDialogVisible.value = true
}

function openProviderEdit(row) {
  providerEditing.value = row
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
  providerDialogVisible.value = true
}

async function submitProvider() {
  if (!providerFormRef.value) return
  try {
    await providerFormRef.value.validate()
  } catch {
    return
  }
  const editing = providerEditing.value
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

    if (editing) {
      await api.updateProvider(editing.key, payload)
      ElMessage.success(`供应商 ${payload.label} 已更新`)
    } else {
      payload.key = providerForm.key.trim()
      await api.createProvider(payload)
      ElMessage.success(`供应商 ${payload.label} 已创建`)
    }
    providerDialogVisible.value = false
    await refreshAll()
  } catch {
    // 请求层已统一提示（重复 key / 当前生效不可删等由后端校验）
  } finally {
    providerSaving.value = false
  }
}

async function removeProvider(row) {
  try {
    await api.deleteProvider(row.key)
    ElMessage.success(`供应商 ${row.label || row.key} 已删除`)
    await refreshAll()
  } catch {
    // 请求层已统一提示（仅自定义、非当前生效可删）
  }
}

function openProviderTest(row) {
  providerTestTarget.value = row
  providerTestModel.value = ''
  providerTestResult.value = null
  providerTestVisible.value = true
}

function resetProviderTest() {
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
      message: adminErrorMessage(e, '测试请求失败，请检查网关与后端日志')
    }
  } finally {
    providerTestRunning.value = false
  }
}

onMounted(() => {
  load()
  // 管理员专属接口：非管理员不请求、不渲染对应卡片
  if (canWrite.value) {
    loadNotifications()
    loadProviders()
  }
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
.provider-card.off {
  opacity: 0.55;
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

/* AI 供应商管理 */
.provider-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.provider-cell {
  min-width: 0;
}
.provider-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.price-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  font-size: 12px;
  line-height: 1.5;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.provider-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.provider-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}

/* 供应商表单 / 测试对话框 */
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
.delete-wrap {
  display: inline-flex;
}
.test-target {
  margin-bottom: 14px;
  padding: 10px 12px;
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
:global(.provider-dialog .el-dialog__body) {
  max-height: 64vh;
  overflow-y: auto;
  padding-top: 8px;
}
@media (max-width: 760px) {
  .price-fields {
    flex-direction: column;
  }
}
</style>
