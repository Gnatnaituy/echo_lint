<template>
  <!-- ① 当前生效 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.active.title') }}</div>
      <span class="tiny dim">{{ t('settings.active.subtitle') }}</span>
    </div>
    <div class="panel-body">
      <div v-if="view" class="active-card">
        <div class="active-left">
          <div class="active-mark" :class="view.apiKeyConfigured ? 'ok' : 'warn'">
            <el-icon :size="19"><Cpu /></el-icon>
          </div>
          <div class="active-info">
            <div class="active-line">
              <span class="active-provider">{{ view.providerLabel }}</span>
              <span class="active-sep">·</span>
              <span class="active-model mono">{{ view.model }}</span>
              <span class="chip" :class="view.source === 'DATABASE' ? 'brand' : 'info'">
                {{
                  view.source === 'DATABASE'
                    ? t('settings.source.uiSwitch')
                    : t('settings.source.configDefault')
                }}
              </span>
            </div>
            <div class="active-status">
              <span
                class="dot"
                :style="{ background: view.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"
              ></span>
              <span :class="view.apiKeyConfigured ? 'dim' : 'warn-text'">
                {{
                  view.apiKeyConfigured
                    ? t('settings.active.keyConfigured')
                    : t('settings.active.keyMissing')
                }}
              </span>
            </div>
          </div>
        </div>
        <el-button
          v-if="canWrite"
          :loading="testing === 'active'"
          :disabled="testing === 'draft'"
          @click="testActive"
        >
          <el-icon v-if="testing !== 'active'" class="el-icon--left"><Connection /></el-icon>
          {{ t('settings.active.testConnection') }}
        </el-button>
      </div>
      <div v-else class="empty-block">{{ t('common.loading') }}</div>

      <div v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'bad'">
        <el-icon :size="16">
          <component :is="testResult.ok ? CircleCheckFilled : CircleCloseFilled" />
        </el-icon>
        <div class="test-text">
          <div class="test-head">
            <b>{{ testResult.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
            <span class="mono dim">{{ testResult.providerLabel }} · {{ testResult.model }}</span>
            <span v-if="testResult.ok" class="num dim">{{
              t('settings.test.latency', { ms: testResult.latencyMs })
            }}</span>
          </div>
          <div class="test-msg">
            {{
              testResult.ok ? testResult.reply || t('settings.test.emptyReply') : testResult.message
            }}
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { useI18n } from '../../i18n'
import { useSettingsContext } from './context'
import { CircleCheckFilled, CircleCloseFilled, Connection, Cpu } from '../../components/icons.js'

const { t } = useI18n()
const { canWrite, model } = useSettingsContext()
// ① 与 ② 共享同一份 view / testing / testResult：任一侧发起测试，结果都显示在本卡片内
const { view, testing, testResult, testActive } = model
</script>

<style scoped>
/* 当前生效卡片 */
.active-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.active-left {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.active-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--r-md);
}
.active-mark.ok {
  background: var(--brand-50);
  color: var(--brand-600);
}
.active-mark.warn {
  background: var(--warn-soft);
  color: #b06a06;
}
.active-info {
  min-width: 0;
}
.active-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.active-provider {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
}
.active-sep {
  color: var(--ink-300);
}
.active-model {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-600);
}
/* 手机：当前模型卡一行放不下（左侧信息被压到 87px，模型名与状态逐字换行），
   让「测试连通性」按钮换到下一行 */
@media (max-width: 720px) {
  .active-card {
    flex-wrap: wrap;
    gap: 10px;
  }
  .active-model {
    white-space: nowrap;
  }
}
.active-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 5px;
  font-size: 12px;
}
.warn-text {
  color: #b06a06;
}

/* 测试结果 */
.test-result {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  font-size: 12.5px;
  line-height: 1.7;
}
.test-result.ok {
  background: var(--ok-soft);
  color: #14672f;
}
.test-result.bad {
  background: var(--danger-soft);
  color: #a32424;
}
.test-text {
  min-width: 0;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.test-msg {
  margin-top: 2px;
  word-break: break-all;
  opacity: 0.85;
}
</style>
