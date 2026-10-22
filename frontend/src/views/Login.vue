<template>
  <div class="login-page">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

    <section class="login-card">
      <!-- 品牌 -->
      <div class="brand">
        <div class="brand-mark">
          <el-icon :size="20"><Headset /></el-icon>
        </div>
        <div class="brand-text">
          <div class="brand-name">EchoLint</div>
          <div class="brand-sub">Audio Compliance Audit</div>
        </div>
      </div>

      <div class="welcome">
        <h1>{{ t('login.title') }}</h1>
        <p>{{ t('login.subtitle') }}</p>
      </div>

      <el-alert
        v-if="error"
        class="login-error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item :label="t('login.username')" prop="username">
          <el-input
            ref="usernameRef"
            v-model="form.username"
            :placeholder="t('login.usernamePlaceholder')"
            autocomplete="username"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item :label="t('login.password')" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="t('login.passwordPlaceholder')"
            show-password
            autocomplete="current-password"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          class="submit"
          :loading="submitting"
        >
          {{ t('login.submit') }}
        </el-button>
      </el-form>

      <div class="foot">
        <el-icon :size="12"><Lock /></el-icon>
        <span>{{ t('login.foot') }}</span>
      </div>
    </section>

    <footer class="copyright">{{ t('login.copyright') }}</footer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import auth from '../auth'
import { safeRedirect } from '../constants'
import { useI18n } from '../i18n'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const formRef = ref(null)
const usernameRef = ref(null)
const submitting = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

const rules = computed(() => ({
  username: [{ required: true, message: t('login.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.passwordRequired'), trigger: 'blur' }]
}))

async function submit() {
  if (!formRef.value) return
  error.value = ''
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const user = await auth.login(form.username, form.password)
    ElMessage.success(
      t('login.welcome', { name: user?.displayName || user?.username || '' })
    )
    // 登录后回到被拦截的原地址（仅接受站内路径，避免开放重定向）
    router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = e?.message || t('login.error')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  usernameRef.value?.focus?.()
})
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 44px 20px;
  overflow-x: hidden;
  overflow-y: auto;
  background:
    radial-gradient(1000px 520px at 8% -12%, #dbe6ff 0%, rgba(244, 246, 251, 0) 62%),
    radial-gradient(760px 460px at 102% -4%, #e6edfd 0%, rgba(244, 246, 251, 0) 58%),
    linear-gradient(180deg, #f7f9fd 0%, #eef2f9 100%);
}

/* 背景光斑，避免纯色过于扁平 */
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
  pointer-events: none;
}
.glow-a {
  width: 420px;
  height: 420px;
  left: -140px;
  bottom: -160px;
  background: radial-gradient(circle, rgba(59, 110, 246, 0.14), rgba(59, 110, 246, 0) 68%);
}
.glow-b {
  width: 360px;
  height: 360px;
  right: -120px;
  top: -120px;
  background: radial-gradient(circle, rgba(59, 110, 246, 0.1), rgba(59, 110, 246, 0) 66%);
}

.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 408px;
  padding: 32px 32px 24px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 20px;
  box-shadow: 0 22px 56px rgba(15, 23, 42, 0.1), 0 2px 8px rgba(15, 23, 42, 0.04);
  animation: card-in 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-mark {
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: 13px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 6px 16px rgba(59, 110, 246, 0.32);
}
.brand-name {
  font-size: 16.5px;
  font-weight: 650;
  color: var(--ink-800);
  letter-spacing: 0.01em;
  line-height: 1.25;
}
.brand-sub {
  margin-top: 1px;
  font-size: 10.5px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.welcome {
  margin: 26px 0 20px;
}
.welcome h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
  color: var(--ink-800);
  letter-spacing: -0.01em;
  line-height: 1.35;
}
.welcome p {
  margin: 7px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.login-error {
  margin-bottom: 16px;
}
.login-error :deep(.el-alert) {
  padding: 10px 12px;
}
.login-error :deep(.el-alert__title) {
  font-size: 12.5px;
  line-height: 1.6;
}

/* 表单节奏：标签 13px，输入框与标签间距统一 */
.login-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.login-card :deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
  line-height: 1.4;
  padding-bottom: 6px;
}
.login-card :deep(.el-input__wrapper) {
  padding: 1px 12px;
}
.login-card :deep(.el-input__inner) {
  font-size: 14px;
}
/* 自动聚焦与键盘 Tab 都保持一致的品牌色聚焦环（错误态仍用红色） */
.login-card :deep(.el-form-item:not(.is-error) .el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 1px var(--brand-500) inset, 0 0 0 3px rgba(59, 110, 246, 0.14);
}

.submit {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-placeholder);
}

.copyright {
  position: absolute;
  bottom: 22px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  color: var(--ink-400);
  letter-spacing: 0.04em;
}

@media (max-width: 480px) {
  .login-page {
    padding: 28px 16px;
  }
  .login-card {
    padding: 26px 22px 20px;
  }
}
</style>
