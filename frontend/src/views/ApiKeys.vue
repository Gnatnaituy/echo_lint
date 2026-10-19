<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">开放 API 密钥</div>
          <span class="chip info">{{ total }} 个密钥</span>
          <span v-if="enabledCount" class="chip ok">{{ enabledCount }} 个启用中</span>
        </div>
        <div class="actions">
          <el-button :icon="Refresh" circle :loading="loading" @click="load" aria-label="刷新" title="刷新" />
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">创建密钥</el-button>
        </div>
      </div>
      <div class="hint tiny dim">
        外部系统通过请求头 <span class="mono">X-API-Key</span> 调用
        <span class="mono">/api/open/**</span>（如上传录音、查询处理状态）。
        密钥只在创建时显示一次明文，服务端仅保存 SHA-256 哈希；停用后立即拒绝请求但保留审计记录。
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="pagedRows">
        <el-table-column label="名称" min-width="190">
          <template #default="{ row }">
            <div class="min-w-0">
              <div class="strong ellipsis" :title="row.name">{{ row.name || '未命名密钥' }}</div>
              <div class="tiny dim mono">#{{ row.id ?? '—' }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="密钥" min-width="200">
          <template #default="{ row }">
            <span class="chip mono">{{ maskedApiKey(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'">
              {{ row.enabled === false ? '已停用' : row.enabled === true ? '启用' : '未知' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="创建人" width="130">
          <template #default="{ row }">
            <span v-if="row.createdBy" class="ellipsis" :title="row.createdBy">{{ row.createdBy }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">
            <span v-if="row.lastUsedAt" class="num" :title="formatRelative(row.lastUsedAt)">
              {{ formatTime(row.lastUsedAt) }}
            </span>
            <span v-else class="dim">从未使用</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span v-if="row.createdAt" class="num dim">{{ formatTime(row.createdAt) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="96" align="right">
          <template #default="{ row }">
            <el-popconfirm
              v-if="row.enabled !== false && row.id != null"
              title="停用后使用该密钥的请求会立即返回 401，确认停用？"
              width="260"
              @confirm="revoke(row)"
            >
              <template #reference>
                <el-button size="small" link type="danger" :loading="revokingId === row.id">
                  停用
                </el-button>
              </template>
            </el-popconfirm>
            <span v-else class="tiny dim">已停用</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Key /></el-icon>
            <div class="empty-title">还没有 API 密钥</div>
            <div class="tiny dim">
              创建密钥后，外部系统即可用 <span class="mono">X-API-Key</span> 头调用开放接口
            </div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="(p) => (page = p)"
        />
      </div>
    </section>

    <!-- 创建密钥 -->
    <el-dialog
      v-model="createVisible"
      title="创建 API 密钥"
      width="470px"
      append-to-body
      :close-on-click-modal="!creating"
      @closed="resetCreate"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent="submitCreate">
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            placeholder="用途 / 调用方，如 工单系统"
            autocomplete="off"
          />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="创建后明文密钥只显示一次，请当场复制保存；之后列表只能看到前缀掩码。"
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建密钥</el-button>
      </template>
    </el-dialog>

    <!-- 一次性明文 -->
    <el-dialog
      v-model="secretVisible"
      title="密钥已创建"
      width="580px"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="secret-head">
        <div class="secret-icon"><el-icon :size="18"><Key /></el-icon></div>
        <div class="min-w-0">
          <div class="strong">{{ createdSecret.name || '未命名密钥' }}</div>
          <div class="tiny dim">仅显示一次：关闭后无法再次查看完整密钥</div>
        </div>
      </div>
      <div class="secret-box">
        <el-input
          :model-value="createdSecret.key || '（后端未返回明文密钥）'"
          readonly
          type="textarea"
          :rows="2"
          class="mono"
        />
        <el-button type="primary" :icon="CopyDocument" :disabled="!createdSecret.key" @click="copySecret">
          复制密钥
        </el-button>
      </div>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="secret-alert"
        title="请立即妥善保存：密钥泄露后请停用并重新创建；不要提交到代码仓库或写入日志。"
      />
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Plus, Refresh } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import { formatRelative, formatTime } from '../constants'
import { maskedApiKey } from '../utils/apiKeys'

const canWrite = computed(() => auth.hasRole('ADMIN'))
const pageSize = 10

const rows = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const revokingId = ref(null)

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize
  return rows.value.slice(start, start + pageSize)
})
const enabledCount = computed(() => rows.value.filter((r) => r.enabled !== false).length)

/** 403 / 404 给出可读降级文案（列表接口 silent，不弹全局错误） */
function apiKeyErrorMessage(err) {
  const status = err?.status
  if (status === 403) return '当前账号无权限访问 API 密钥（需要 ADMIN）'
  if (status === 404) return '后端尚未提供 API 密钥接口（HTTP 404），请确认后端版本'
  return err?.message || 'API 密钥加载失败，请稍后重试'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listApiKeys()
    rows.value = data.items
    total.value = data.total
    const maxPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (page.value > maxPage) page.value = maxPage
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = apiKeyErrorMessage(e)
  } finally {
    loading.value = false
  }
}

/* ---------- 创建 / 一次性明文 ---------- */

const createVisible = ref(false)
const creating = ref(false)
const formRef = ref(null)
const form = reactive({ name: '' })
const rules = {
  name: [
    { required: true, message: '请输入密钥名称', trigger: 'blur' },
    { min: 1, max: 64, message: '名称 1-64 个字符', trigger: 'blur' }
  ]
}

const secretVisible = ref(false)
const createdSecret = reactive({ name: '', key: '' })

function resetCreate() {
  form.name = ''
  formRef.value?.clearValidate?.()
}

function openCreate() {
  resetCreate()
  createVisible.value = true
}

function resetSecret() {
  createdSecret.name = ''
  createdSecret.key = ''
}

async function submitCreate() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  creating.value = true
  try {
    const created = await api.createApiKey({ name: form.name.trim() })
    createVisible.value = false
    resetSecret()
    createdSecret.name = created.name || form.name.trim()
    createdSecret.key = created.key || ''
    secretVisible.value = true
    page.value = 1
    await load()
  } catch {
    // 请求层已统一提示（名称重复 / 403 等）
  } finally {
    creating.value = false
  }
}

/** 复制一次性明文：优先 Clipboard API，非安全上下文回退 execCommand */
async function copySecret() {
  const text = createdSecret.key
  if (!text) return
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    ElMessage.success('密钥已复制到剪贴板')
  } catch {
    let area = null
    try {
      area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      const ok = document.execCommand('copy')
      if (ok) ElMessage.success('密钥已复制到剪贴板')
      else ElMessage.warning('复制失败，请手动选择密钥复制')
    } catch {
      ElMessage.warning('复制失败，请手动选择密钥复制')
    } finally {
      // 失败路径也要清理临时节点，避免残留隐藏 textarea
      if (area?.parentNode) area.parentNode.removeChild(area)
    }
  }
}

async function revoke(row) {
  if (revokingId.value != null) return
  revokingId.value = row.id
  try {
    await api.revokeApiKey(row.id)
    ElMessage.success(`密钥「${row.name || row.id}」已停用`)
    await load()
  } catch {
    // 请求层已统一提示
  } finally {
    revokingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hint {
  padding: 0 18px 14px;
  line-height: 1.7;
}
.min-w-0 {
  min-width: 0;
}
.ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}
.empty-icon {
  color: var(--ink-300);
}

/* 一次性明文弹窗 */
.secret-head {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 12px;
}
.secret-icon {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
}
.secret-box {
  display: flex;
  align-items: stretch;
  gap: 10px;
}
.secret-box :deep(.el-textarea) {
  flex: 1;
}
.secret-alert {
  margin-top: 12px;
}

.error-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 14px 18px 0;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
}
</style>
