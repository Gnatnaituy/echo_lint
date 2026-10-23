<template>
  <div class="page stack">
    <!-- 概览 -->
    <div class="kpi-grid">
      <StatCard
        :label="t('corpus.kpi.total')"
        :value="(stats.violation || 0) + (stats.compliant || 0)"
        icon="Notebook"
        tone="brand"
        :sub="t('corpus.kpi.totalSub')"
      />
      <StatCard
        :label="t('corpus.kpi.violation')"
        :value="stats.violation"
        icon="WarningFilled"
        tone="danger"
        :sub="t('corpus.kpi.violationSub')"
        clickable
        :active="labelFilter === 'VIOLATION'"
        @click="setLabel('VIOLATION')"
      />
      <StatCard
        :label="t('corpus.kpi.compliant')"
        :value="stats.compliant"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="t('corpus.kpi.compliantSub')"
        clickable
        :active="labelFilter === 'COMPLIANT'"
        @click="setLabel('COMPLIANT')"
      />
    </div>

    <!-- 闭环说明 -->
    <section class="panel loop">
      <div class="panel-body loop-body">
        <div class="loop-icon"><el-icon :size="18"><Refresh /></el-icon></div>
        <div class="loop-text">
          <div class="loop-title">{{ t('corpus.loop.title') }}</div>
          <div class="loop-steps">
            <span class="step"><b>1</b> {{ t('corpus.loop.step1') }}</span>
            <span class="step"><b>2</b> {{ t('corpus.loop.step2') }}</span>
            <span class="step"><b>3</b> {{ t('corpus.loop.step3') }}</span>
          </div>
        </div>
        <el-button :icon="Download" @click="doExport">{{ t('corpus.exportJson') }}</el-button>
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters">
          <SegmentedControl
            v-model="labelFilter"
            :options="labelOptions"
            :aria-label="t('corpus.table.label')"
            @change="reload"
          />
        </div>
        <div class="actions">
          <el-input
            v-model="keyword"
            :placeholder="t('corpus.searchPlaceholder')"
            clearable
            class="search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" :aria-label="t('common.refresh')" :title="t('common.refresh')" />
          <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('corpus.create') }}</el-button>
        </div>
      </div>

      <el-table ref="tableRef" v-loading="loading" :data="rows">
        <el-table-column :label="t('corpus.table.label')" width="88" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.label === 'VIOLATION' ? 'danger' : 'ok'">
              {{ row.label === 'VIOLATION' ? t('meta.evaluationLabel.VIOLATION') : t('meta.evaluationLabel.COMPLIANT') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('corpus.table.violationType')" width="140" align="center">
          <template #default="{ row }">
            <span v-if="row.violationType && VIOLATION_TYPES[row.violationType]" class="chip warn">
              {{ VIOLATION_TYPES[row.violationType] }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('corpus.table.transcript')" min-width="250">
          <template #default="{ row }">
            <el-tooltip :content="row.transcript" placement="top" :show-after="400">
              <div class="corpus-text">{{ row.transcript }}</div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column v-if="!compactColumns" :label="t('corpus.table.reason')" min-width="180">
          <template #default="{ row }">
            <span
              :class="{ dim: !row.reason }"
              class="reason-text"
              :title="row.reason || ''"
            >{{ row.reason || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('corpus.table.source')" width="132" align="center">
          <template #default="{ row }">
            <span class="chip" :class="SOURCE_CLASS[row.source] || 'info'">
              {{ SOURCE_LABEL[row.source] || row.source }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.createdAt')" width="124">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="78" align="right" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-popconfirm v-if="canDelete" :title="t('corpus.confirmDelete')" @confirm="remove(row)">
                <template #reference>
                  <el-button size="small" link type="danger">{{ t('common.delete') }}</el-button>
                </template>
              </el-popconfirm>
              <span v-else class="dim">—</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">{{ t('corpus.empty.title') }}</div>
            <div class="tiny dim">{{ t('corpus.empty.hint') }}</div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page + 1"
          @current-change="(p) => { page = p - 1; load() }"
        />
      </div>
    </section>

    <!-- 新增 -->
    <el-dialog v-model="dialogVisible" :title="t('corpus.dialog.createTitle')" width="560px">
      <el-form :model="form" label-width="82px">
        <el-form-item :label="t('corpus.table.label')">
          <div class="seg">
            <button
              class="seg-item"
              :class="{ on: form.label === 'VIOLATION' }"
              @click="form.label = 'VIOLATION'"
            >{{ t('corpus.kpi.violation') }}</button>
            <button
              class="seg-item"
              :class="{ on: form.label === 'COMPLIANT' }"
              @click="form.label = 'COMPLIANT'"
            >{{ t('corpus.kpi.compliant') }}</button>
          </div>
        </el-form-item>
        <el-form-item v-if="form.label === 'VIOLATION'" :label="t('corpus.table.violationType')">
          <div class="types-grid">
            <button
              v-for="(label, code) in VIOLATION_TYPES"
              :key="code"
              class="type-chip"
              :class="{ on: form.violationType === code }"
              @click="form.violationType = code"
            >{{ label }}</button>
          </div>
        </el-form-item>
        <el-form-item :label="t('corpus.table.transcript')">
          <el-input
            v-model="form.transcript"
            type="textarea"
            :rows="4"
            :placeholder="t('corpus.form.transcriptPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('corpus.table.reason')">
          <el-input v-model="form.reason" :placeholder="t('corpus.form.reasonPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus, Download } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import StatCard from '../components/StatCard.vue'
import SegmentedControl from '../components/SegmentedControl.vue'
import { VIOLATION_TYPES, formatTimeShort } from '../constants'
import { useElementWidth } from '../utils/elementWidth'
import { useI18n } from '../i18n'

const { t } = useI18n()

// 语料删除仅 ADMIN（见权限矩阵），其余角色只读
const canDelete = computed(() => auth.hasRole('ADMIN'))

const rows = ref([])

/* 窄屏收起「标注理由」（详情行内不再挤成一列 3 字），让固定操作列不再压住创建时间 */
const tableRef = ref(null)
const tableWidth = useElementWidth(tableRef)
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 900)
const total = ref(0)
const page = ref(0)
const pageSize = 10
const loading = ref(false)
const labelFilter = ref('')
const keyword = ref('')
const overview = ref(null)

const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ label: 'VIOLATION', violationType: 'INSULT', transcript: '', reason: '' })

/* 枚举型过滤统一用分段控件：全部 / 违规 / 合规 */
const labelOptions = computed(() => [
  { value: '', label: t('common.all') },
  { value: 'VIOLATION', label: t('corpus.kpi.violation') },
  { value: 'COMPLIANT', label: t('corpus.kpi.compliant') }
])

const SOURCE_LABEL = computed(() => ({
  REVIEW: t('corpus.source.REVIEW'),
  SEED: t('corpus.source.SEED'),
  MANUAL: t('corpus.source.MANUAL')
}))
const SOURCE_CLASS = { REVIEW: 'brand', SEED: 'info', MANUAL: 'ok' }

const stats = computed(() => ({
  violation: overview.value?.corpus?.violation ?? 0,
  compliant: overview.value?.corpus?.compliant ?? 0
}))

async function loadOverview() {
  try {
    overview.value = await api.overview()
  } catch {
    // 忽略
  }
}

async function load() {
  loading.value = true
  try {
    const data = await api.listCorpus({
      label: labelFilter.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: pageSize
    })
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 0
  load()
}

function setLabel(v) {
  labelFilter.value = labelFilter.value === v ? '' : v
  reload()
}

function openCreate() {
  form.value = { label: 'VIOLATION', violationType: 'INSULT', transcript: '', reason: '' }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.transcript?.trim()) {
    ElMessage.warning(t('corpus.messages.transcriptRequired'))
    return
  }
  saving.value = true
  try {
    await api.createCorpus(form.value)
    ElMessage.success(t('corpus.messages.created'))
    dialogVisible.value = false
    reload()
    loadOverview()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await api.deleteCorpus(row.id)
  ElMessage.success(t('corpus.messages.deleted'))
  load()
  loadOverview()
}

async function doExport() {
  try {
    const blob = await api.exportCorpus()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `corpus_${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error(t('corpus.messages.exportFailed'))
  }
}

onMounted(() => {
  load()
  loadOverview()
})
</script>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
/* 手机：三列会把标题/说明挤成多行，改成两列 */
@media (max-width: 720px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}

/* 闭环说明 */
.loop-body {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  row-gap: 12px;
}
.loop-icon {
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 11px;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
}
.loop-text {
  flex: 1 1 320px;
  min-width: 0;
}
.loop-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 6px;
}
.loop-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}
.step {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.step b {
  width: 17px;
  height: 17px;
  border-radius: 50%;
  background: var(--brand-100);
  color: var(--brand-600);
  font-size: 11px;
  display: grid;
  place-items: center;
}

/* 工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
}
.filters {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  width: 230px;
}

/* 多行文本列：统一 2 行截断 + 完整内容走 tooltip / title */
.corpus-text {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.reason-text {
  font-size: 12.5px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

/* 操作列 */
.op-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  white-space: nowrap;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}

/* 弹窗分段 */
.seg {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 10px;
  padding: 3px;
  gap: 3px;
}
.seg-item {
  height: 30px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
}
.seg-item.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.types-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.type-chip {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.type-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.type-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
}
.empty-title {
  font-weight: 600;
  color: var(--ink-600);
  margin-bottom: 4px;
}
</style>
