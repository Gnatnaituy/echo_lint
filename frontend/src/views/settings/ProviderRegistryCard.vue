<template>
  <!-- ③ AI 供应商（ADMIN）：注册表管理，仍可切换当前生效供应商 / 模型 -->
  <section class="panel">
    <div class="panel-head">
      <div class="section-title">{{ t('settings.provider.title') }}</div>
      <div class="provider-head">
        <span class="tiny dim">{{ t('settings.provider.subtitle') }}</span>
        <el-button size="small" :loading="providersLoading" @click="load">{{
          t('common.refresh')
        }}</el-button>
        <el-button size="small" type="primary" @click="openProviderCreate">
          <el-icon class="el-icon--left"><Plus /></el-icon>
          {{ t('settings.provider.create') }}
        </el-button>
      </div>
    </div>
    <div v-loading="providersLoading" class="panel-body">
      <!-- 接口未上线 / 无权限（403、404）时降级为可重试提示，不影响上方切换模型 -->
      <div v-if="providersError" class="provider-fallback">
        <el-icon :size="16"><WarningFilled /></el-icon>
        <div class="provider-fallback-text">
          <div class="strong">{{ t('settings.provider.unavailable') }}</div>
          <div class="tiny dim">{{ providersError }}</div>
        </div>
        <el-button size="small" @click="load">{{ t('common.retry') }}</el-button>
      </div>

      <template v-else>
        <!-- 表格贴齐卡片左右边缘：首列文字与 panel-head 标题在 18px 基线上对齐（避免 18+18 双重缩进） -->
        <div ref="providerTableWrap" class="table-bleed">
          <el-table :data="providers" size="small">
            <el-table-column :label="t('settings.provider.columnName')" min-width="180">
              <template #default="{ row }">
                <div class="provider-cell">
                  <div class="provider-name">
                    <span class="strong">{{ row.label || row.key || t('common.untitled') }}</span>
                    <span v-if="providerIsActive(row)" class="chip brand">{{
                      t('settings.provider.current')
                    }}</span>
                    <span v-if="row.builtIn" class="chip info">{{
                      t('settings.provider.builtIn')
                    }}</span>
                  </div>
                  <div class="mono tiny dim">{{ row.key || '—' }}</div>
                </div>
              </template>
            </el-table-column>

            <el-table-column :label="t('settings.provider.baseUrl')" min-width="190">
              <template #default="{ row }">
                <div class="provider-cell">
                  <div class="mono tiny ellipsis" :title="row.baseUrl">
                    {{ row.baseUrl || '—' }}
                  </div>
                  <div class="mono tiny dim ellipsis" :title="row.chatPath">
                    {{ row.chatPath || t('settings.provider.defaultChatPath') }}
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column :label="t('settings.provider.defaultModel')" min-width="142">
              <template #default="{ row }">
                <div class="mono tiny ellipsis" :title="row.defaultModel">
                  {{ row.defaultModel || '—' }}
                </div>
                <div class="tiny dim">
                  {{ t('settings.provider.modelCount', { count: row.models.length }) }}
                </div>
              </template>
            </el-table-column>

            <el-table-column
              v-if="!compactProviderTable"
              :label="t('settings.provider.pricePer1k')"
              width="126"
              align="right"
            >
              <template #header>
                <span :title="t('settings.provider.priceTooltip')">{{
                  t('settings.provider.pricePer1k')
                }}</span>
              </template>
              <template #default="{ row }">
                <div class="price-cell">
                  <span class="price-row">
                    <span class="price-label">{{ t('settings.provider.priceIn') }}</span>
                    <span class="mono">{{ formatPricePer1k(row.inputPricePer1k) }}</span>
                  </span>
                  <span class="price-row">
                    <span class="price-label">{{ t('settings.provider.priceOut') }}</span>
                    <span class="mono">{{ formatPricePer1k(row.outputPricePer1k) }}</span>
                  </span>
                </div>
              </template>
            </el-table-column>

            <el-table-column :label="t('settings.provider.keyColumn')" width="108">
              <template #default="{ row }">
                <span class="chip mono key-chip" :class="providerKeyChip(row).type">
                  {{ providerKeyChip(row).label }}
                </span>
              </template>
            </el-table-column>

            <el-table-column :label="t('common.status')" width="82" align="right">
              <template #default="{ row }">
                <span
                  class="chip"
                  :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'"
                >
                  {{
                    row.enabled === false
                      ? t('common.disabled')
                      : row.enabled === true
                        ? t('common.enabled')
                        : t('common.unknown')
                  }}
                </span>
              </template>
            </el-table-column>

            <el-table-column :label="t('common.actions')" width="138" align="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openProviderTest(row)">{{
                  t('settings.provider.test')
                }}</el-button>
                <el-button size="small" link type="primary" @click="openProviderEdit(row)">{{
                  t('common.edit')
                }}</el-button>
                <el-tooltip
                  v-if="!row.builtIn"
                  :disabled="!providerIsActive(row)"
                  :content="t('settings.provider.activeDeleteTooltip')"
                  placement="top"
                >
                  <span class="delete-wrap">
                    <el-popconfirm
                      :title="t('settings.provider.deleteConfirm')"
                      width="270"
                      @confirm="removeProvider(row)"
                    >
                      <template #reference>
                        <el-button
                          size="small"
                          link
                          type="danger"
                          :disabled="providerIsActive(row)"
                        >
                          {{ t('common.delete') }}
                        </el-button>
                      </template>
                    </el-popconfirm>
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>

            <template #empty>
              <div class="empty-block">
                <el-icon :size="34" class="empty-icon"><Cpu /></el-icon>
                <div class="empty-title">{{ t('settings.provider.empty') }}</div>
                <div class="tiny dim">
                  {{ t('settings.provider.emptyHint') }}
                </div>
              </div>
            </template>
          </el-table>
        </div>

        <div class="hint tiny dim">
          {{ t('settings.provider.hintA') }}
          {{ t('settings.provider.hintB') }}
        </div>
      </template>
    </div>
  </section>

  <!-- 新增 / 编辑供应商、连通性测试：仅随本卡片异步加载 -->
  <ProviderFormDialog ref="formDialog" @saved="refreshAll" />
  <ProviderTestDialog ref="testDialog" />
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import { formatPricePer1k } from '../../utils/providers'
import { useElementWidth } from '../../utils/elementWidth'
import { useSettingsContext } from './context'
import ProviderFormDialog from './ProviderFormDialog.vue'
import ProviderTestDialog from './ProviderTestDialog.vue'
import { Cpu, Plus, WarningFilled } from '../../components/icons.js'

const { t } = useI18n()
const { model, registry } = useSettingsContext()
const { providers, providersLoading, providersError, load, providerIsActive } = registry

/* 供应商表窄屏收起「价格 / 1K」，避免固定操作列压住密钥/状态。
   阈值取 990：1280 笔记本（表格约 958px）下价格让位给「接入地址 / 默认模型」，
   这两列才是选供应商时真正要读的信息。 */
const providerTableWrap = ref(null)
const providerTableWidth = useElementWidth(providerTableWrap)
const compactProviderTable = computed(
  () => providerTableWidth.value > 0 && providerTableWidth.value < 990
)

const formDialog = ref(null)
const testDialog = ref(null)

/** 密钥状态：只展示后端掩码，不接触明文 */
function providerKeyChip(row) {
  const masked = row?.apiKeyMasked || ''
  if (row?.apiKeyConfigured === true) {
    return { label: masked || t('settings.state.configured'), type: 'ok' }
  }
  if (row?.apiKeyConfigured === false) {
    return { label: t('settings.state.notConfigured'), type: 'warn' }
  }
  return { label: masked || t('common.unknown'), type: 'info' }
}

/** 供应商增删改 / 测试后同时刷新注册表与「当前生效」视图，避免切换区显示旧数据 */
async function refreshAll() {
  await Promise.all([model.load(), registry.load()])
}

function openProviderCreate() {
  formDialog.value?.open(null)
}

function openProviderEdit(row) {
  formDialog.value?.open(row)
}

function openProviderTest(row) {
  testDialog.value?.open(row)
}

async function removeProvider(row) {
  try {
    await api.deleteProvider(row.key)
    ElMessage.success(t('settings.provider.deleted', { label: row.label || row.key }))
    await refreshAll()
  } catch {
    // 请求层已统一提示（仅自定义、非当前生效可删）
  }
}
</script>

<style scoped>
/* AI 供应商管理 */
.provider-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 嵌套在 .panel-body 中的表格：左右各吃掉 body 的 18px 内边距，
   让表格贴到卡片边缘、首列文字与 panel-head 标题同处 18px 基线。
   单元格自身内边距仍由全局 .el-table .cell（18px）提供，不叠加。 */
.table-bleed {
  margin-left: calc(-1 * var(--panel-pad-x, 18px));
  margin-right: calc(-1 * var(--panel-pad-x, 18px));
}
.table-bleed :deep(.el-table .cell) {
  padding-left: var(--table-pad-x, 18px);
  padding-right: var(--table-pad-x, 18px);
}
.provider-cell {
  min-width: 0;
}
.provider-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
/* 名称行的状态胶囊更紧凑，保证 1280 下「当前 + 内置」不换行 */
.provider-name .chip {
  padding: 0 7px;
}
.price-cell {
  display: grid;
  grid-template-columns: auto auto;
  justify-content: end;
  column-gap: 6px;
  row-gap: 1px;
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
}
.price-row {
  display: contents;
  font-variant-numeric: tabular-nums;
}
.price-cell .mono {
  text-align: right;
}
.price-label {
  justify-self: end;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.key-chip {
  padding: 0 7px;
  font-size: 11.5px;
  letter-spacing: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.provider-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.provider-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
.delete-wrap {
  display: inline-flex;
}
.hint {
  margin-top: 12px;
  line-height: 1.7;
}
</style>
