<template>
  <header class="topbar">
    <div class="left">
      <button
        ref="menuBtn"
        type="button"
        class="menu-btn"
        :aria-label="t('a11y.openMenu')"
        :title="t('a11y.openMenu')"
        @click="$emit('toggleMenu')"
      >
        <el-icon :size="18"><Expand /></el-icon>
      </button>
      <div class="crumb">
        <span class="crumb-root">{{ t('header.platform') }}</span>
        <el-icon class="crumb-sep" :size="12"><ArrowRight /></el-icon>
        <span class="crumb-current">{{ title || t('nav.dashboard') }}</span>
      </div>
    </div>

    <div class="right">
      <button
        class="ghost status-btn"
        :class="healthy ? 'is-ok' : 'is-bad'"
        :aria-label="t('a11y.refreshStatus')"
        :title="t('a11y.refreshStatus')"
        @click="$emit('refresh')"
      >
        <span class="dot" :style="{ background: healthy ? '#16a34a' : '#ef4444' }"></span>
        <span class="ghost-text">{{ healthy ? t('header.serviceOk') : t('header.serviceBad') }}</span>
        <el-icon :size="12" class="ghost-refresh"><Refresh /></el-icon>
      </button>

      <button
        class="ghost review-btn"
        :aria-label="t('a11y.pendingReview')"
        :title="t('a11y.pendingReview')"
        @click="router.push('/review')"
      >
        <el-icon :size="14"><Bell /></el-icon>
        <span class="ghost-text">{{ t('header.pendingReview') }}</span>
        <span class="count" :class="{ zero: !pending }">{{ pending > 99 ? '99+' : pending }}</span>
      </button>

      <!-- 语言切换：中文 / EN，持久化到 localStorage -->
      <button
        type="button"
        class="ghost lang-btn"
        :aria-label="t('a11y.language')"
        :title="t('a11y.language')"
        @click="onToggleLocale"
      >
        {{ locale === 'zh' ? 'EN' : '中文' }}
      </button>

      <div class="divider"></div>

      <!-- 当前登录用户：显示名 + 角色；菜单含修改密码 / 退出登录 -->
      <el-dropdown trigger="click" popper-class="user-dropdown" @command="onCommand">
        <button
          type="button"
          class="user"
          :title="t('a11y.accountMenu')"
          :aria-label="t('a11y.accountMenu')"
        >
          <span class="avatar">{{ avatarText }}</span>
          <span class="user-meta">
            <span class="user-name">{{ auth.displayName || t('common.unknown') }}</span>
            <span class="user-role">{{ roleSubtitle }}</span>
          </span>
          <el-icon class="user-caret" :size="12"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              <div class="menu-user">
                <div class="menu-name">{{ auth.displayName || auth.username || t('common.unknown') }}</div>
                <div class="menu-sub">{{ auth.username || '—' }} · {{ auth.roleLabel }}</div>
              </div>
            </el-dropdown-item>
            <el-dropdown-item command="password" divided>
              <el-icon><Lock /></el-icon>
              {{ t('header.changePassword') }}
            </el-dropdown-item>
            <el-dropdown-item command="logout">
              <el-icon><SwitchButton /></el-icon>
              {{ t('header.logout') }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <!-- 修改密码 -->
    <el-dialog v-model="pwdVisible" :title="t('password.title')" width="430px" append-to-body @closed="resetPwd">
      <el-form
        ref="pwdFormRef"
        :model="pwdForm"
        :rules="pwdRules"
        label-width="88px"
        @submit.prevent="submitPassword"
      >
        <el-form-item :label="t('password.old')" prop="oldPassword">
          <el-input
            v-model="pwdForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            :placeholder="t('password.oldPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('password.new')" prop="newPassword">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="t('password.newPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('password.confirm')" prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="t('password.confirmPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPassword">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </header>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import auth from '../auth'
import api from '../api'
import { useI18n } from '../i18n'

defineProps({
  title: { type: String, default: '' },
  pending: { type: Number, default: 0 },
  healthy: { type: Boolean, default: true }
})
defineEmits(['refresh', 'toggleMenu'])

const router = useRouter()
const { t, locale, toggleLocale } = useI18n()

/** 窄屏汉堡按钮：供 App.vue 在关闭抽屉后把焦点还回来 */
const menuBtn = ref(null)
function focusMenuButton() {
  menuBtn.value?.focus?.()
}
defineExpose({ focusMenuButton })

function onToggleLocale() {
  toggleLocale()
}

const avatarText = computed(() => {
  const name = auth.displayName || auth.username || ''
  return name ? name.slice(0, 1).toUpperCase() : '?'
})

/** 顶栏副标题：显示名与角色同名时（如 admin 的显示名也叫「管理员」）改显 @用户名，避免上下两行重复 */
const roleSubtitle = computed(() => {
  const name = auth.displayName || ''
  const role = auth.roleLabel || ''
  if (name && role && name !== role) return role
  return auth.username ? `@${auth.username}` : role
})

/* ---------- 账号菜单 ---------- */
function onCommand(command) {
  if (command === 'password') {
    resetPwd()
    pwdVisible.value = true
  } else if (command === 'logout') {
    logout()
  }
}

async function logout() {
  await auth.logout()
  ElMessage.success(t('header.logoutSuccess'))
  router.replace('/login')
}

/* ---------- 修改密码 ---------- */
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

function validateConfirm(rule, value, callback) {
  if (!value) return callback(new Error(t('password.confirmRequired')))
  if (value !== pwdForm.newPassword) return callback(new Error(t('password.mismatch')))
  callback()
}

const pwdRules = computed(() => ({
  oldPassword: [{ required: true, message: t('password.oldRequired'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('password.newRequired'), trigger: 'blur' },
    { min: 8, message: t('password.min'), trigger: 'blur' }
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
}))

function resetPwd() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdFormRef.value?.clearValidate?.()
}

async function submitPassword() {
  if (!pwdFormRef.value) return
  try {
    await pwdFormRef.value.validate()
  } catch {
    return
  }
  pwdSaving.value = true
  try {
    await api.changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success(t('password.success'))
    pwdVisible.value = false
  } catch {
    // 请求层已统一提示（原密码错误 / 403 等）
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.topbar {
  height: 58px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1 1 auto;
}
/* 窄屏汉堡按钮：桌面隐藏 */
.menu-btn {
  display: none;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: #fff;
  color: var(--ink-600);
  cursor: pointer;
}
.menu-btn:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
  background: var(--brand-50);
}
.lang-btn {
  letter-spacing: 0.03em;
  font-weight: 600;
}
@media (max-width: 1320px) {
  .topbar {
    padding: 0 16px;
    gap: 10px;
  }
  .ghost {
    padding: 0 10px;
  }
  .right {
    gap: 8px;
  }
}
/* 中等桌面宽度：顶栏只保留「当前页面 + 图标化操作」，避免文字挤成两行 */
@media (max-width: 1180px) {
  .crumb-root,
  .crumb-sep {
    display: none;
  }
  .ghost-text {
    display: none;
  }
}
.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  min-width: 0;
  white-space: nowrap;
}
.crumb-root {
  color: var(--el-text-color-placeholder);
  flex: none;
}
.crumb-sep {
  color: var(--ink-300);
  flex: none;
}
.crumb-current {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
}

.right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
  /* 顶栏空间不足时只允许彼此靠拢，绝不允许文字换行把 58px 顶栏撑破 */
  white-space: nowrap;
  flex: none;
}
.ghost:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
  background: var(--brand-50);
}
.ghost.is-ok {
  color: #12823b;
  border-color: #cfe9d8;
  background: #f4fbf6;
}
.ghost.is-bad {
  color: #c92c2c;
  border-color: #f7c9c9;
  background: #fdf5f5;
}
.ghost:hover .ghost-refresh {
  transform: rotate(90deg);
}
.ghost-refresh {
  transition: transform 0.3s ease;
  opacity: 0.6;
}
.ghost .dot {
  width: 6px;
  height: 6px;
}

.count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.count.zero {
  background: var(--ink-200);
  color: var(--ink-500);
}

.divider {
  width: 1px;
  height: 22px;
  background: var(--border);
  margin: 0 2px;
}

/* 用户菜单按钮（重置原生 button 样式，保持原有视觉） */
.user {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 40px;
  padding: 0 8px 0 4px;
  border: none;
  border-radius: 10px;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.16s ease;
}
.user:hover {
  background: var(--ink-50);
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
}
.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;
}
.user-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-700);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-role {
  font-size: 10.5px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.04em;
}
.user-caret {
  color: var(--ink-400);
}

/* 窄屏覆盖必须放在基础规则之后：同权重下靠后的规则才生效，
   否则「隐藏用户名/角色」会被后面的基础 .user-meta 反向覆盖，
   顶栏标题被挤成 0 宽（手机端表现为页面标题消失）。 */
@media (max-width: 900px) {
  .topbar {
    height: 52px;
    padding: 0 12px;
    gap: 8px;
  }
  .left {
    gap: 6px;
  }
  .menu-btn {
    display: inline-grid;
  }
  .crumb-root,
  .crumb-sep {
    display: none;
  }
  .crumb-current {
    font-size: 13.5px;
    min-width: 56px;
  }
  .right {
    gap: 6px;
  }
  .ghost {
    height: 30px;
    padding: 0 8px;
  }
  .ghost-text,
  .divider {
    display: none;
  }
  .user {
    height: 34px;
    padding: 0 4px;
  }
  .user-meta {
    display: none;
  }
  .lang-btn {
    padding: 0 8px;
  }
}
</style>
