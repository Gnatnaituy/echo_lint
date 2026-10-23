<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('components.assign.title')"
    width="440px"
    append-to-body
    @update:model-value="(v) => emit('update:modelValue', v)"
    @open="onOpen"
  >
    <div v-if="recording" class="assign-target">
      <el-icon :size="14"><Microphone /></el-icon>
      <span class="ellipsis">{{ recording.fileName || t('components.assign.recordingFallback', { id: recording.id }) }}</span>
      <span class="chip mono">#{{ recording.id }}</span>
    </div>

    <el-form label-position="top" @submit.prevent="confirm">
      <el-form-item :label="t('components.assign.reviewer')">
        <el-select
          v-model="username"
          filterable
          allow-create
          default-first-option
          :placeholder="t('components.assign.reviewerPlaceholder')"
          style="width: 100%"
          :loading="usersLoading"
        >
          <el-option
            v-for="u in reviewerOptions"
            :key="u.username"
            :label="t('components.assign.optionLabel', { name: u.displayName || u.username, username: u.username })"
            :value="u.username"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="assign-hint tiny dim">
      {{ t('components.assign.hint') }}
      <span v-if="usersError" class="assign-error">{{ usersError }}</span>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!username" @click="confirm">{{ t('components.assign.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useI18n } from '../i18n'

const { t } = useI18n()

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  recording: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'assigned'])

const users = ref([])
const usersLoading = ref(false)
const usersError = ref('')
const username = ref('')
const saving = ref(false)

/** 只列可复核角色（ADMIN / REVIEWER），禁用账号不参与指派 */
const reviewerOptions = computed(() =>
  (users.value || []).filter(
    (u) => u && u.enabled !== false && ['ADMIN', 'REVIEWER'].includes(u.role)
  )
)

async function loadUsers() {
  if (users.value.length) return
  usersError.value = ''
  usersLoading.value = true
  try {
    const data = await api.listUsers()
    users.value = Array.isArray(data) ? data : data?.content || []
  } catch {
    usersError.value = t('components.assign.usersLoadFailed')
  } finally {
    usersLoading.value = false
  }
}

function onOpen() {
  username.value = props.recording?.assignedTo || ''
  loadUsers()
}

async function confirm() {
  const target = String(username.value || '').trim()
  if (!props.recording?.id || !target) return
  saving.value = true
  try {
    const data = await api.assignRecording(props.recording.id, target)
    ElMessage.success(t('components.assign.success', { name: target }))
    emit('assigned', { username: target, data })
    emit('update:modelValue', false)
    username.value = ''
  } catch {
    // 请求层已统一提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.assign-target {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--ink-600);
  min-width: 0;
}
.assign-target .ellipsis {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assign-hint {
  line-height: 1.65;
}
.assign-error {
  display: block;
  color: #b06a06;
}
</style>
