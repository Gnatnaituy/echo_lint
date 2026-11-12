<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">{{ t('users.title') }}</div>
          <span class="chip info">{{ tn('users.accountCount', rows.length) }}</span>
          <span class="chip brand">{{ tn('users.adminCount', adminCount) }}</span>
          <span v-if="disabledCount" class="chip">
            {{ t('users.disabledCount', { count: disabledCount }) }}
          </span>
        </div>
        <div class="actions">
          <el-input
            v-model="keyword"
            :placeholder="t('users.searchPlaceholder')"
            clearable
            class="search"
            @clear="page = 1"
          >
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
          <el-button
            :icon="Refresh"
            circle
            :aria-label="t('common.refresh')"
            :title="t('common.refresh')"
            @click="load"
          />
          <el-button type="primary" :icon="Plus" @click="openCreate">{{
            t('users.create')
          }}</el-button>
        </div>
      </div>
      <div class="hint tiny dim">
        <div>{{ t('users.roleHint') }}</div>
        <div>{{ t('users.menuHint') }}</div>
        <div>{{ t('users.securityHint') }}</div>
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">{{
          t('common.retry')
        }}</el-button>
      </div>

      <el-table v-loading="loading" :data="pagedRows" :row-class-name="rowClass">
        <el-table-column :label="t('users.username')" min-width="190">
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
        <el-table-column prop="displayName" :label="t('users.displayName')" min-width="140">
          <template #default="{ row }">
            <span :class="{ dim: !row.displayName }">{{ row.displayName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('users.role')" width="110" align="center">
          <template #default="{ row }">
            <span class="chip" :class="ROLE_CHIP[row.role] || 'info'">
              {{ roleLabel(row.role) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('users.menus')" width="104" align="center">
          <template #default="{ row }">
            <el-tooltip :content="menuSummary(row)" placement="top" :disabled="!row.menuRestricted">
              <span class="chip" :class="row.menuRestricted ? 'brand' : 'info'">
                {{
                  row.menuRestricted
                    ? tn('users.menusCount', menuCount(row), { count: menuCount(row) })
                    : t('users.menusAll')
                }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.enabled ? 'ok' : 'off'">
              {{ row.enabled ? t('common.enabled') : t('users.statusDisabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.createdAt')" width="150">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="176" align="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button size="small" link type="primary" @click="openEdit(row)">
                {{ t('common.edit') }}
              </el-button>
              <el-popconfirm
                v-if="row.enabled && !isSelf(row)"
                :title="t('users.disableConfirm')"
                width="240"
                @confirm="disable(row)"
              >
                <template #reference>
                  <el-button size="small" link type="danger">{{ t('users.disable') }}</el-button>
                </template>
              </el-popconfirm>
              <el-button
                v-else-if="!row.enabled"
                size="small"
                link
                type="success"
                @click="enable(row)"
                >{{ t('users.enable') }}</el-button
              >
              <span v-else class="tiny dim">{{ t('users.self') }}</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><User /></el-icon>
            <div class="empty-title">
              {{ rows.length ? t('users.emptyFilteredTitle') : t('users.emptyTitle') }}
            </div>
            <div class="tiny dim">
              {{ rows.length ? t('users.emptyFilteredHint') : t('users.emptyHint') }}
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
      :title="editing ? t('users.editTitle') : t('users.createTitle')"
      width="560px"
      append-to-body
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="86px"
        @submit.prevent="submit"
      >
        <el-form-item :label="t('users.username')" prop="username">
          <el-input
            v-if="!editing"
            v-model="form.username"
            :placeholder="t('users.usernamePlaceholder')"
            autocomplete="off"
          />
          <el-input v-else :model-value="editing.username" disabled />
        </el-form-item>
        <el-form-item :label="t('users.displayName')" prop="displayName">
          <el-input
            v-model="form.displayName"
            :placeholder="t('users.displayNamePlaceholder')"
            maxlength="40"
          />
        </el-form-item>
        <el-form-item :label="t('users.role')" prop="role">
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
        <el-form-item :label="t('users.menus')" prop="menuKeys">
          <div class="menu-field">
            <div class="tiny dim menu-hint">{{ t('users.menusHint') }}</div>
            <div class="menu-actions">
              <span class="tiny dim">
                {{
                  t('users.menusRoleScope', {
                    role: roleLabel(form.role),
                    count: allowedMenuKeys.length
                  })
                }}
              </span>
              <span class="menu-actions-btns">
                <el-button link type="primary" size="small" @click="selectAllMenus">
                  {{ t('users.menusSelectAll') }}
                </el-button>
                <el-button link size="small" @click="clearMenus">
                  {{ t('users.menusClear') }}
                </el-button>
              </span>
            </div>
            <div v-for="group in menuGroupsForForm" :key="group.titleKey" class="menu-group">
              <div class="menu-group-head">
                <span class="menu-group-title">{{ t(group.titleKey) }}</span>
                <span class="tiny dim">{{ group.selectedCount }}/{{ group.items.length }}</span>
                <el-button
                  link
                  size="small"
                  class="menu-group-toggle"
                  @click="toggleMenuGroup(group)"
                >
                  {{ group.allSelected ? t('users.menusClear') : t('users.menusSelectAll') }}
                </el-button>
              </div>
              <el-checkbox-group v-model="form.menuKeys" class="menu-checkboxes">
                <el-checkbox v-for="item in group.items" :key="item.key" :value="item.key">
                  {{ t(item.labelKey) }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
        <el-form-item
          :label="editing ? t('users.resetPassword') : t('users.initialPassword')"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="
              editing ? t('users.passwordKeepPlaceholder') : t('users.passwordPlaceholder')
            "
          />
        </el-form-item>
        <el-form-item v-if="editing" :label="t('common.enabled')">
          <el-switch v-model="form.enabled" />
          <span class="tiny dim ml-8">{{ t('users.disableHint') }}</span>
        </el-form-item>
        <el-alert
          v-if="editing && isSelf(editing)"
          type="warning"
          :closable="false"
          show-icon
          :title="t('users.selfAlert')"
          class="self-alert"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editing ? t('users.saveChanges') : t('users.createSubmit') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import { formatTimeShort, ROLE_META, ROLE_OPTIONS, roleLabel } from '../constants'
import { useI18n } from '../i18n'
import { MENU_BY_KEY, MENU_GROUPS, MENU_ITEMS, reconcileMenuSelection } from '../menu.js'
import { User, WarningFilled } from '../components/icons.js'

const { t, tn } = useI18n()

const rows = ref([])
/** 可绑定菜单目录（后端 MenuKey）：请求失败时用本地目录兜底，保证弹窗始终可编辑 */
const menuOptions = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = 10

const ROLE_CHIP = { ADMIN: 'brand', REVIEWER: 'warn', VIEWER: 'info' }

/** 可绑定菜单的来源：后端目录优先，缺失时回退本地目录（两者 key 一致） */
function menuOptionSource() {
  return menuOptions.value.length
    ? menuOptions.value
    : MENU_ITEMS.map((item) => ({ key: item.key, adminOnly: !!item.adminOnly }))
}

/** 指定角色允许绑定的菜单 key 全集（ADMIN 全部；其余仅非 adminOnly） */
function allowedMenuKeysForRole(role) {
  return menuOptionSource()
    .filter((opt) => role === 'ADMIN' || !opt.adminOnly)
    .map((opt) => opt.key)
    .filter((key) => MENU_BY_KEY[key])
}

function menuCount(row) {
  return Array.isArray(row?.menuKeys) ? row.menuKeys.length : 0
}

function menuSummary(row) {
  const keys = Array.isArray(row?.menuKeys) ? row.menuKeys : []
  const menus = keys
    .map((key) => (MENU_BY_KEY[key] ? t(MENU_BY_KEY[key].labelKey) : key))
    .join(' / ')
  return t('users.menusSummary', { menus })
}

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter(
    (r) =>
      String(r.username || '')
        .toLowerCase()
        .includes(kw) ||
      String(r.displayName || '')
        .toLowerCase()
        .includes(kw)
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
  return String(row?.displayName || row?.username || '?')
    .slice(0, 1)
    .toUpperCase()
}
function isSelf(row) {
  return !!row?.username && row.username === auth.username
}
function rowClass({ row }) {
  return row?.enabled === false ? 'row-off' : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listUsers()
    rows.value = Array.isArray(data) ? data : data?.content || []
  } catch (e) {
    rows.value = []
    error.value = e?.message || t('users.loadFailed')
  } finally {
    loading.value = false
  }
}

/** 拉取可绑定菜单目录；失败时静默回退本地目录，不阻断用户列表 */
async function loadMenuOptions() {
  try {
    const data = await api.listMenuOptions()
    menuOptions.value = Array.isArray(data) ? data : []
  } catch {
    menuOptions.value = []
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
  enabled: true,
  menuKeys: []
})

/** 程序化回填表单时暂停「角色切换 → 菜单自动调整」，避免覆盖行上已绑定的菜单 */
let roleSyncPaused = false

const allowedMenuKeys = computed(() => allowedMenuKeysForRole(form.role))
const allowedMenuKeySet = computed(() => new Set(allowedMenuKeys.value))

/** 弹窗内按侧栏分组展示的菜单复选：只显示当前角色允许的项，并统计每组已选数量 */
const menuGroupsForForm = computed(() =>
  MENU_GROUPS.map((group) => {
    const items = group.items.filter((item) => allowedMenuKeySet.value.has(item.key))
    const selectedCount = items.filter((item) => form.menuKeys.includes(item.key)).length
    return {
      ...group,
      items,
      selectedCount,
      allSelected: items.length > 0 && selectedCount === items.length
    }
  }).filter((group) => group.items.length > 0)
)

function selectAllMenus() {
  form.menuKeys = [...allowedMenuKeys.value]
}

function clearMenus() {
  form.menuKeys = []
}

function toggleMenuGroup(group) {
  const keys = group.items.map((item) => item.key)
  if (group.allSelected) {
    form.menuKeys = form.menuKeys.filter((key) => !keys.includes(key))
  } else {
    form.menuKeys = Array.from(new Set([...form.menuKeys, ...keys]))
  }
}

// 角色切换时收窄已选菜单：原来「全选旧角色可用项」则自动全选新角色可用项，
// 否则保留交集；交集为空时回填新角色可用项，避免出现「一个菜单都没选」的死状态。
watch(
  () => form.role,
  (role, previousRole) => {
    if (roleSyncPaused) return
    form.menuKeys = reconcileMenuSelection(
      form.menuKeys,
      allowedMenuKeysForRole(previousRole),
      allowedMenuKeysForRole(role)
    )
  }
)

function validatePassword(rule, value, callback) {
  if (editing.value && !value) return callback()
  if (!value) return callback(new Error(t('users.passwordRequired')))
  if (String(value).length < 8) return callback(new Error(t('users.passwordMin')))
  callback()
}

function validateMenus(rule, value, callback) {
  if (!Array.isArray(value) || value.length === 0) {
    return callback(new Error(t('users.menusRequired')))
  }
  callback()
}

const rules = computed(() => ({
  username: [
    { required: true, message: t('users.usernameRequired'), trigger: 'blur' },
    {
      pattern: /^[a-z0-9._-]{3,32}$/,
      message: t('users.usernamePattern'),
      trigger: 'blur'
    }
  ],
  displayName: [{ required: true, message: t('users.displayNameRequired'), trigger: 'blur' }],
  role: [{ required: true, message: t('users.roleRequired'), trigger: 'change' }],
  menuKeys: [{ validator: validateMenus, trigger: 'change' }],
  password: [{ validator: validatePassword, trigger: 'blur' }]
}))

function resetForm() {
  editing.value = null
  form.username = ''
  form.displayName = ''
  form.role = 'REVIEWER'
  form.password = ''
  form.enabled = true
  form.menuKeys = []
  formRef.value?.clearValidate?.()
}

function openCreate() {
  // 回填期间暂停角色联动；下一次 watcher flush 结束后再恢复（nextTick 必须在 mutation 之后调用，
  // 否则会先于 watcher 恢复，导致后面的回填被联动逻辑覆盖）
  roleSyncPaused = true
  resetForm()
  form.menuKeys = allowedMenuKeysForRole('REVIEWER')
  dialogVisible.value = true
  nextTick(() => {
    roleSyncPaused = false
  })
}

function openEdit(row) {
  roleSyncPaused = true
  editing.value = row
  form.username = row.username || ''
  form.displayName = row.displayName || ''
  form.role = row.role || 'REVIEWER'
  form.password = ''
  form.enabled = row.enabled !== false
  const allowed = allowedMenuKeysForRole(form.role)
  const allowedSet = new Set(allowed)
  const effective = Array.isArray(row.menuKeys)
    ? row.menuKeys.filter((key) => allowedSet.has(key))
    : []
  // 不限制（全部可见）时回填角色允许全集；受限时回填已绑定菜单
  form.menuKeys = row.menuRestricted && effective.length ? effective : allowed
  dialogVisible.value = true
  nextTick(() => {
    roleSyncPaused = false
  })
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
    const menuKeys = [...form.menuKeys]
    if (editing.value) {
      const payload = {
        displayName,
        role: form.role,
        enabled: form.enabled,
        menuKeys
      }
      if (form.password) payload.password = form.password
      await api.updateUser(editing.value.id, payload)
      ElMessage.success(t('users.updated'))
    } else {
      await api.createUser({
        username: form.username.trim().toLowerCase(),
        password: form.password,
        displayName,
        role: form.role,
        menuKeys
      })
      ElMessage.success(t('users.created'))
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 请求层已统一提示（403 / 最后一个管理员菜单保护等）
  } finally {
    saving.value = false
  }
}

async function disable(row) {
  try {
    await api.disableUser(row.id)
    ElMessage.success(t('users.disabledSuccess', { name: row.username }))
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
    ElMessage.success(t('users.enabledSuccess', { name: row.username }))
    await load()
  } catch {
    // 请求层已统一提示
  }
}

onMounted(() => {
  load()
  loadMenuOptions()
})
</script>

<style scoped>
/* 卡片裁切到圆角：表格/分页栏不再从圆角处露出直角 */
.panel {
  overflow: hidden;
}
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
  flex-wrap: wrap;
}
.search {
  width: 240px;
}
/* 手机：固定 240px 搜索框会把「新建用户」按钮推出卡片外 */
@media (max-width: 900px) {
  .actions {
    width: 100%;
  }
  .search {
    width: 100%;
    flex: 1 1 100%;
  }
}
.hint {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 18px 12px;
  line-height: 1.7;
  color: var(--ink-500);
  font-size: 11.5px;
  background: #fbfcfe;
  border-top: 1px solid var(--border);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  min-width: 0;
}
.avatar {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
  background: linear-gradient(
    140deg,
    var(--brand-400) 0%,
    var(--brand-500) 52%,
    var(--brand-600) 100%
  );
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.35),
    0 2px 6px rgba(47, 88, 197, 0.24);
}
.avatar.off {
  background: var(--ink-300);
}
.chip.off {
  background: var(--ink-100);
  color: var(--el-text-color-secondary);
}
/* 禁用账号整行降权，状态胶囊与置灰头像保持一致 */
.panel :deep(.el-table__row.row-off) .user-cell {
  opacity: 0.7;
}
.panel :deep(.el-table__row.row-off) .strong {
  color: var(--el-text-color-secondary);
}
/* 操作列贴右对齐，链路按钮间距固定，右侧留白由全局 18px cell padding 提供 */
.op-cell {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
.op-cell :deep(.el-button + .el-button) {
  margin-left: 0;
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
/* 分页栏：上边框与卡片底部对齐，左右 18px 内边距与卡片头/表格一致 */
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 10px 18px 12px;
  border-top: 1px solid var(--border);
  background: #fbfcfe;
  border-radius: 0 0 var(--r-lg) var(--r-lg);
}
/* 最后一行不做底边线，避免与分页栏上边框叠成双线 */
.panel :deep(.el-table__body tr:last-child td.el-table__cell) {
  border-bottom: none;
}
.role-field {
  width: 100%;
}
.role-desc {
  margin-top: 4px;
  line-height: 1.5;
}
/* 菜单绑定：分组复选，限高滚动避免把弹窗撑得过高 */
.menu-field {
  width: 100%;
}
.menu-hint {
  line-height: 1.6;
}
.menu-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin: 6px 0 8px;
}
.menu-actions-btns {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.menu-group {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 8px 10px 4px;
  margin-bottom: 8px;
  background: #fcfdff;
}
.menu-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
.menu-group-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-600);
}
.menu-group-toggle {
  margin-left: auto;
}
.menu-checkboxes {
  display: flex;
  flex-wrap: wrap;
  gap: 0 14px;
}
.menu-checkboxes :deep(.el-checkbox) {
  height: 26px;
  margin-right: 0;
  font-size: 12.5px;
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
