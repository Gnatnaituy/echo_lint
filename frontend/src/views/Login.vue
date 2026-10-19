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
  display: grid;
  place-items: center;
  padding: 36px 16px 64px;
  overflow: hidden;
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
  max-width: 400px;
  padding: 30px 30px 22px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r-xl);
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.12), 0 2px 8px rgba(15, 23, 42, 0.04);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}
.brand-mark {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 6px 16px rgba(59, 110, 246, 0.34);
}
.brand-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-800);
  letter-spacing: 0.01em;
  line-height: 1.25;
}
.brand-sub {
  font-size: 10.5px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.welcome {
  margin: 24px 0 18px;
}
.welcome h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-800);
  letter-spacing: -0.01em;
}
.welcome p {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}

.login-error {
  margin-bottom: 16px;
}
.login-error :deep(.el-alert__title) {
  font-size: 12.5px;
  line-height: 1.6;
}

.submit {
  width: 100%;
  margin-top: 4px;
  height: 42px;
  font-size: 14px;
  letter-spacing: 0.14em;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}

.copyright {
  position: absolute;
  bottom: 20px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 11.5px;
  color: var(--ink-400);
  letter-spacing: 0.04em;
}
</style>
