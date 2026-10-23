<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">{{ t('apiKeys.title') }}</div>
          <span class="chip info">{{ tn('apiKeys.count', total) }}</span>
          <span v-if="enabledCount" class="chip ok">
            {{ t('apiKeys.enabledCount', { count: enabledCount }) }}
          </span>
        </div>
        <div class="actions">
          <el-button
            :icon="Refresh"
            circle
            :loading="loading"
            @click="load"
            :aria-label="t('common.refresh')"
            :title="t('common.refresh')"
          />
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
            {{ t('apiKeys.create') }}
          </el-button>
        </div>
      </div>
      <div class="hint tiny dim">
        {{ t('apiKeys.hint') }}
        {{ t('apiKeys.securityHint') }}
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">{{ t('common.retry') }}</el-button>
      </div>

      <el-table ref="tableRef" v-loading="loading" :data="pagedRows">
        <el-table-column :label="t('common.name')" min-width="200">
          <template #default="{ row }">
            <div class="min-w-0">
              <div class="strong ellipsis" :title="row.name">
                {{ row.name || t('apiKeys.untitled') }}
              </div>
              <div class="tiny dim mono">#{{ row.id ?? '—' }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('apiKeys.key')" min-width="180">
          <template #default="{ row }">
            <div class="key-cell">
              <span class="chip mono key-chip" :title="maskedApiKey(row)">{{ maskedApiKey(row) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.status')" width="100" align="center">
          <template #default="{ row }">
            <span
              class="chip"
              :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'"
            >
              {{
                row.enabled === false
                  ? t('apiKeys.statusRevoked')
                  : row.enabled === true
                    ? t('common.enabled')
                    : t('common.unknown')
              }}
            </span>
          </template>
        </el-table-column>

        <el-table-column v-if="!narrowColumns" :label="t('common.createdBy')" width="132">
          <template #default="{ row }">
            <span v-if="row.createdBy" class="ellipsis" :title="row.createdBy">{{ row.createdBy }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('apiKeys.lastUsed')" width="176">
          <template #default="{ row }">
            <span v-if="row.lastUsedAt" class="num" :title="formatRelative(row.lastUsedAt)">
              {{ formatTime(row.lastUsedAt) }}
            </span>
            <span v-else class="dim">{{ t('apiKeys.neverUsed') }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="!compactColumns" :label="t('common.createdAt')" width="176">
          <template #default="{ row }">
            <span v-if="row.createdAt" class="num dim">{{ formatTime(row.createdAt) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.actions')" width="96" align="right" fixed="right">
          <template #default="{ row }">
            <el-popconfirm
              v-if="row.enabled !== false && row.id != null"
              :title="t('apiKeys.revokeConfirm')"
              width="260"
              @confirm="revoke(row)"
            >
              <template #reference>
                <el-button size="small" link type="danger" :loading="revokingId === row.id">
                  {{ t('apiKeys.revoke') }}
                </el-button>
              </template>
            </el-popconfirm>
            <span v-else class="tiny dim">{{ t('apiKeys.statusRevoked') }}</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Key /></el-icon>
            <div class="empty-title">{{ t('apiKeys.emptyTitle') }}</div>
            <div class="tiny dim">{{ t('apiKeys.emptyHint') }}</div>
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
      :title="t('apiKeys.createTitle')"
      width="470px"
      append-to-body
      :close-on-click-modal="!creating"
      @closed="resetCreate"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent="submitCreate">
        <el-form-item :label="t('common.name')" prop="name">
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            :placeholder="t('apiKeys.namePlaceholder')"
            autocomplete="off"
          />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="t('apiKeys.createAlert')"
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">
          {{ t('apiKeys.create') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 一次性明文 -->
    <el-dialog
      v-model="secretVisible"
      :title="t('apiKeys.createdTitle')"
      width="580px"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="secret-head">
        <div class="secret-icon"><el-icon :size="18"><Key /></el-icon></div>
        <div class="min-w-0">
          <div class="strong">{{ createdSecret.name || t('apiKeys.untitled') }}</div>
          <div class="tiny dim">{{ t('apiKeys.createdHint') }}</div>
        </div>
      </div>
      <div class="secret-box">
        <el-input
          :model-value="createdSecret.key || t('apiKeys.plaintextMissing')"
          readonly
          class="secret-input"
          :title="createdSecret.key || ''"
        />
        <el-button type="primary" :icon="CopyDocument" :disabled="!createdSecret.key" @click="copySecret">
          {{ t('apiKeys.copyKey') }}
        </el-button>
      </div>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="secret-alert"
        :title="t('apiKeys.saveAlert')"
      />
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">
          {{ t('apiKeys.savedConfirm') }}
        </el-button>
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
import { useI18n } from '../i18n'
import { maskedApiKey } from '../utils/apiKeys'
import { useElementWidth } from '../utils/elementWidth'

const { t, tn } = useI18n()

const canWrite = computed(() => auth.hasRole('ADMIN'))
const pageSize = 10

const rows = ref([])

/* 表格列显隐按容器宽度：窄屏先收起「创建时间」，再窄收起「创建人」，
   让 1024/1280 下不被固定「操作」列压住「最近使用」。 */
const tableRef = ref(null)
const tableWidth = useElementWidth(tableRef)
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 1060)
const narrowColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 900)
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
  if (status === 403) return t('apiKeys.forbidden')
  if (status === 404) return t('apiKeys.notAvailable')
  return err?.message || t('apiKeys.loadFailed')
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
const rules = computed(() => ({
  name: [
    { required: true, message: t('apiKeys.nameRequired'), trigger: 'blur' },
    { min: 1, max: 64, message: t('apiKeys.nameLength'), trigger: 'blur' }
  ]
}))

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
    ElMessage.success(t('apiKeys.copied'))
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
      if (ok) ElMessage.success(t('apiKeys.copied'))
      else ElMessage.warning(t('apiKeys.copyFailed'))
    } catch {
      ElMessage.warning(t('apiKeys.copyFailed'))
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
    ElMessage.success(t('apiKeys.revokedSuccess', { name: row.name || row.id }))
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
/* 密钥列：等宽展示，超长时截断但不撑破单元格 */
.key-cell {
  min-width: 0;
  overflow: hidden;
}
.key-chip {
  max-width: 100%;
  overflow: hidden;
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
  align-items: center;
  gap: 10px;
}
.secret-box :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.secret-box :deep(.el-input__inner) {
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
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
