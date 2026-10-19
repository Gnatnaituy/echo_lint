<template>
  <header class="topbar">
    <div class="left">
      <div class="crumb">
        <span class="crumb-root">稽核平台</span>
        <el-icon class="crumb-sep" :size="12"><ArrowRight /></el-icon>
        <span class="crumb-current">{{ title || '工作台' }}</span>
      </div>
    </div>

    <div class="right">
      <button class="ghost" :class="healthy ? 'is-ok' : 'is-bad'" @click="$emit('refresh')">
        <span class="dot" :style="{ background: healthy ? '#16a34a' : '#ef4444' }"></span>
        {{ healthy ? '服务正常' : '服务异常' }}
        <el-icon :size="12" class="ghost-refresh"><Refresh /></el-icon>
      </button>

      <button class="ghost" @click="router.push('/review')">
        <el-icon :size="14"><Bell /></el-icon>
        待复检
        <span class="count" :class="{ zero: !pending }">{{ pending > 99 ? '99+' : pending }}</span>
      </button>

      <div class="divider"></div>

      <!-- 当前登录用户：显示名 + 角色；菜单含修改密码 / 退出登录 -->
      <el-dropdown trigger="click" popper-class="user-dropdown" @command="onCommand">
        <button type="button" class="user" title="账号菜单">
          <span class="avatar">{{ avatarText }}</span>
          <span class="user-meta">
            <span class="user-name">{{ auth.displayName || '未登录' }}</span>
            <span class="user-role">{{ auth.roleLabel }}</span>
          </span>
          <el-icon class="user-caret" :size="12"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              <div class="menu-user">
                <div class="menu-name">{{ auth.displayName || auth.username || '未登录' }}</div>
                <div class="menu-sub">{{ auth.username || '—' }} · {{ auth.roleLabel }}</div>
              </div>
            </el-dropdown-item>
            <el-dropdown-item command="password" divided>
              <el-icon><Lock /></el-icon>
              修改密码
            </el-dropdown-item>
            <el-dropdown-item command="logout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <!-- 修改密码 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="430px" append-to-body @closed="resetPwd">
      <el-form
        ref="pwdFormRef"
        :model="pwdForm"
        :rules="pwdRules"
        label-width="88px"
        @submit.prevent="submitPassword"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="pwdForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入当前密码"
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位"
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再次输入新密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPassword">保存</el-button>
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

defineProps({
  title: { type: String, default: '' },
  pending: { type: Number, default: 0 },
  healthy: { type: Boolean, default: true }
})
defineEmits(['refresh'])

const router = useRouter()

const avatarText = computed(() => {
  const name = auth.displayName || auth.username || ''
  return name ? name.slice(0, 1).toUpperCase() : '?'
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
  ElMessage.success('已退出登录')
  router.replace('/login')
}

/* ---------- 修改密码 ---------- */
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

function validateConfirm(rule, value, callback) {
  if (!value) return callback(new Error('请再次输入新密码'))
  if (value !== pwdForm.newPassword) return callback(new Error('两次输入的新密码不一致'))
  callback()
}

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' }
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
}

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
    ElMessage.success('密码修改成功')
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
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}
.crumb-root {
  color: var(--el-text-color-placeholder);
}
.crumb-sep {
  color: var(--ink-300);
}
.crumb-current {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink-700);
}

.right {
  display: flex;
  align-items: center;
  gap: 10px;
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
</style>
