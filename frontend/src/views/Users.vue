<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">用户管理</div>
          <span class="chip info">{{ rows.length }} 个账号</span>
          <span class="chip brand">管理员 {{ adminCount }}</span>
          <span v-if="disabledCount" class="chip">{{ disabledCount }} 个已禁用</span>
        </div>
        <div class="actions">
          <el-input
            v-model="keyword"
            placeholder="搜索用户名 / 显示名"
            clearable
            class="search"
            @clear="page = 1"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="load" aria-label="刷新" title="刷新" />
          <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
        </div>
      </div>
      <div class="hint tiny dim">
        角色：<b>管理员</b> 拥有全部权限；<b>复核员</b> 可上传、认领与提交复核结论；<b>只读</b> 仅可查看。
        禁用账号不会删除历史数据与审计记录，可随时重新启用；密码以 BCrypt 存储，最少 8 位。
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
        <el-table-column label="用户名" min-width="190">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="avatar" :class="{ off: !row.enabled }">{{ initial(row) }}</div>
              <div class="min-w-0">
                <div class="strong ellipsis">{{ row.username }}</div>
                <div class="tiny dim">#{{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="显示名" min-width="140">
          <template #default="{ row }">
            <span :class="{ dim: !row.displayName }">{{ row.displayName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="110" align="center">
          <template #default="{ row }">
            <span class="chip" :class="ROLE_CHIP[row.role] || 'info'">
              {{ roleLabel(row.role) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.enabled ? 'ok' : 'info'">
              {{ row.enabled ? '启用' : '已禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm
              v-if="row.enabled && !isSelf(row)"
              title="禁用后该账号将无法登录，确认禁用？"
              width="240"
              @confirm="disable(row)"
            >
              <template #reference>
                <el-button size="small" link type="danger">禁用</el-button>
              </template>
            </el-popconfirm>
            <el-button
              v-else-if="!row.enabled"
              size="small"
              link
              type="success"
              @click="enable(row)"
            >启用</el-button>
            <span v-else class="tiny dim">当前账号</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><User /></el-icon>
            <div class="empty-title">{{ rows.length ? '没有匹配的账号' : '暂无用户' }}</div>
            <div class="tiny dim">
              {{ rows.length ? '换个关键词试试' : '点击右上角「新建用户」创建第一个账号' }}
            </div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="filteredRows.length"
          :page-size="pageSize"
          :current-page="page"
          @current-change="(p) => (page = p)"
        />
      </div>
    </section>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="470px"
      append-to-body
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="86px" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input
            v-if="!editing"
            v-model="form.username"
            placeholder="登录账号，小写字母/数字/._-"
            autocomplete="off"
          />
          <el-input v-else :model-value="editing.username" disabled />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="如：张合规" maxlength="40" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <div class="role-field">
            <el-select v-model="form.role" style="width: 100%">
              <el-option
                v-for="opt in ROLE_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <div class="tiny dim role-desc">{{ ROLE_META[form.role]?.description }}</div>
          </div>
        </el-form-item>
        <el-form-item :label="editing ? '重置密码' : '初始密码'" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editing ? '留空则不修改（至少 8 位）' : '至少 8 位'"
          />
        </el-form-item>
        <el-form-item v-if="editing" label="启用">
          <el-switch v-model="form.enabled" />
          <span class="tiny dim ml-8">禁用后该账号无法登录，历史审计保留</span>
        </el-form-item>
        <el-alert
          v-if="editing && isSelf(editing)"
          type="warning"
          :closable="false"
          show-icon
          title="这是当前登录账号，修改角色/禁用可能影响本会话的权限"
          class="self-alert"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editing ? '保存修改' : '创建用户' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import { formatTimeShort, ROLE_META, ROLE_OPTIONS, roleLabel } from '../constants'

const rows = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = 10

const ROLE_CHIP = { ADMIN: 'brand', REVIEWER: 'warn', VIEWER: 'info' }

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter(
    (r) =>
      String(r.username || '').toLowerCase().includes(kw) ||
      String(r.displayName || '').toLowerCase().includes(kw)
  )
})
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize
  return filteredRows.value.slice(start, start + pageSize)
})
const adminCount = computed(() => rows.value.filter((r) => r.role === 'ADMIN').length)
const disabledCount = computed(() => rows.value.filter((r) => r.enabled === false).length)

watch(filteredRows, () => {
  const max = Math.max(1, Math.ceil(filteredRows.value.length / pageSize))
  if (page.value > max) page.value = max
})

function initial(row) {
  return String(row?.displayName || row?.username || '?').slice(0, 1).toUpperCase()
}
function isSelf(row) {
  return !!row?.username && row.username === auth.username
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listUsers()
    rows.value = Array.isArray(data) ? data : data?.content || []
  } catch (e) {
    rows.value = []
    error.value = e?.message || '用户列表加载失败'
  } finally {
    loading.value = false
  }
}

/* ---------- 新建 / 编辑 ---------- */
const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const formRef = ref(null)
const form = reactive({
  username: '',
  displayName: '',
  role: 'REVIEWER',
  password: '',
  enabled: true
})

function validatePassword(rule, value, callback) {
  if (editing.value && !value) return callback()
  if (!value) return callback(new Error('请输入初始密码'))
  if (String(value).length < 8) return callback(new Error('密码至少 8 位'))
  callback()
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[a-z0-9._-]{3,32}$/,
      message: '3-32 位小写字母/数字/._-',
      trigger: 'blur'
    }
  ],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [{ validator: validatePassword, trigger: 'blur' }]
}

function resetForm() {
  editing.value = null
  form.username = ''
  form.displayName = ''
  form.role = 'REVIEWER'
  form.password = ''
  form.enabled = true
  formRef.value?.clearValidate?.()
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.username = row.username || ''
  form.displayName = row.displayName || ''
  form.role = row.role || 'REVIEWER'
  form.password = ''
  form.enabled = row.enabled !== false
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const displayName = form.displayName.trim()
    if (editing.value) {
      const payload = {
        displayName,
        role: form.role,
        enabled: form.enabled
      }
      if (form.password) payload.password = form.password
      await api.updateUser(editing.value.id, payload)
      ElMessage.success('用户已更新')
    } else {
      await api.createUser({
        username: form.username.trim().toLowerCase(),
        password: form.password,
        displayName,
        role: form.role
      })
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 请求层已统一提示（403 等）
  } finally {
    saving.value = false
  }
}

async function disable(row) {
  try {
    await api.disableUser(row.id)
    ElMessage.success(`已禁用 ${row.username}`)
    await load()
  } catch {
    // 请求层已统一提示
  }
}

async function enable(row) {
  try {
    await api.updateUser(row.id, {
      displayName: row.displayName || row.username,
      role: row.role,
      enabled: true
    })
    ElMessage.success(`已启用 ${row.username}`)
    await load()
  } catch {
    // 请求层已统一提示
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
.search {
  width: 240px;
}
.hint {
  padding: 0 18px 14px;
  line-height: 1.7;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
}
.avatar.off {
  background: var(--ink-300);
}
.min-w-0 {
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  margin: 8px 0 4px;
  font-weight: 600;
  color: var(--ink-600);
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}
.role-field {
  width: 100%;
}
.role-desc {
  margin-top: 4px;
  line-height: 1.5;
}
.ml-8 {
  margin-left: 8px;
}
.self-alert {
  margin-top: 2px;
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
