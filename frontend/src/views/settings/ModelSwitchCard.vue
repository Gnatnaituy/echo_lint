<template>
  <!-- ② 切换 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.model.title') }}</div>
      <span class="tiny dim">{{ t('settings.model.subtitle') }}</span>
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
            :title="isProviderDisabled(p.id) ? t('settings.model.providerDisabled') : p.baseUrl"
            @click="pickProvider(p)"
          >
            <div class="pc-head">
              <span class="pc-name">{{ p.label }}</span>
              <span v-if="p.active" class="chip brand">{{ t('settings.provider.current') }}</span>
            </div>
            <div class="pc-url mono">{{ p.baseUrl }}</div>
            <div class="pc-foot">
              <span
                class="dot"
                :style="{ background: p.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"
              ></span>
              <span class="tiny">
                {{
                  p.apiKeyConfigured
                    ? t('settings.model.keyConfigured')
                    : p.apiKeyEnv
                      ? t('settings.model.keyMissingEnv', { env: p.apiKeyEnv })
                      : t('settings.model.keyMissing')
                }}
              </span>
            </div>
          </button>
        </div>

        <div class="switch-row">
          <div class="field">
            <label class="field-label">{{ t('settings.model.label') }}</label>
            <el-select
              v-model="draft.model"
              class="model-select"
              filterable
              allow-create
              default-first-option
              :placeholder="t('settings.model.placeholder')"
            >
              <el-option
                v-for="m in currentProvider?.models || []"
                :key="m"
                :label="m"
                :value="m"
              />
            </el-select>
          </div>
          <div class="switch-actions">
            <el-button
              :loading="testing === 'draft'"
              :disabled="!draft.providerId"
              @click="testDraft"
            >
              {{ t('settings.model.test') }}
            </el-button>
            <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">
              {{ t('settings.model.save') }}
            </el-button>
          </div>
        </div>
        <div class="hint tiny dim">
          {{ t('settings.model.hintSelect') }}
          {{ t('settings.model.hintEffect') }}
        </div>
      </template>
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        :title="t('settings.model.readonlyAlert')"
      />
    </div>
  </section>
</template>

<script setup>
import { useI18n } from '../../i18n'
import { useSettingsContext } from './context'

const { t } = useI18n()
const { canWrite, model, registry } = useSettingsContext()
const { view, draft, currentProvider, dirty, saving, testing, pickProvider, save, testDraft } =
  model
// 停用供应商来自注册表（仅 ADMIN 加载；非 ADMIN 不渲染供应商网格，无需该数据）
const { isProviderDisabled } = registry
</script>

<style scoped>
/* 供应商卡片 */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
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
  margin-top: 12px;
  line-height: 1.7;
}
</style>
