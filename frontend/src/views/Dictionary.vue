<template>
  <div class="page stack">
    <!-- 概览 -->
    <div class="kpi-grid">
      <StatCard
        :label="t('dictionary.kpi.total')"
        :value="stats.total"
        icon="Collection"
        tone="brand"
        :sub="t('dictionary.kpi.enabledSub', { count: stats.enabled })"
        clickable
        :active="!sourceFilter && !enabledFilter"
        @click="clearFilters"
      />
      <StatCard
        :label="t('dictionary.kpi.enabled')"
        :value="stats.enabled"
        icon="Select"
        tone="ok"
        :sub="t('dictionary.kpi.enabledHint')"
        clickable
        :active="enabledFilter === true"
        @click="filterEnabled(true)"
      />
      <StatCard
        :label="t('dictionary.kpi.minedPending')"
        :value="stats.minedPending"
        icon="MagicStick"
        tone="warn"
        :sub="t('dictionary.kpi.minedPendingHint')"
        clickable
        :active="sourceFilter === 'MINED' && enabledFilter === false"
        @click="filterMined"
      />
    </div>

    <!-- 检测工具 + 使用说明 -->
    <div class="two-col">
      <section class="panel dfa-panel">
        <div class="panel-head">
          <div class="section-title">{{ t('dictionary.dfa.title') }}</div>
          <span class="tiny dim">{{ t('dictionary.dfa.subtitle') }}</span>
        </div>
        <div class="panel-body">
          <template v-if="canWrite">
            <div class="dfa-field">
              <el-input
                v-model="testText"
                type="textarea"
                :rows="3"
                :placeholder="t('dictionary.dfa.placeholder')"
              />
            </div>
            <div class="dfa-actions">
              <el-button type="primary" :icon="MagicStick" :loading="testing" @click="runTest">
                {{ t('dictionary.dfa.test') }}
              </el-button>
              <el-button v-if="tested" link @click="clearTest">{{ t('dictionary.dfa.clear') }}</el-button>
              <span v-if="tested && testHits.length" class="chip danger">
                {{ t('dictionary.dfa.hitCount', { count: testHits.length }) }}
              </span>
              <span v-else-if="tested" class="chip ok">{{ t('dictionary.dfa.noHit') }}</span>
            </div>

            <div v-if="tested && testText" class="preview">
              <div class="preview-label">{{ t('dictionary.dfa.preview') }}</div>
              <TranscriptViewer :text="testText" :hits="testHits" />
            </div>
          </template>
          <div v-else class="readonly-hint">
            <el-icon :size="15"><Lock /></el-icon>
            <span>{{ t('dictionary.dfa.readonlyHint') }}</span>
          </div>
        </div>
      </section>

      <section class="panel rules-panel">
        <div class="panel-head">
          <div class="section-title">{{ t('dictionary.rules.title') }}</div>
        </div>
        <div class="panel-body tips">
          <div class="tip">
            <el-icon class="tip-icon"><InfoFilled /></el-icon>
            <div>
              <b>{{ t('dictionary.rules.caseInsensitive') }}</b>{{ t('dictionary.rules.caseInsensitiveDesc') }}<code class="mono">fuck</code>{{ t('dictionary.rules.caseInsensitiveMatches') }}<code class="mono">F U C K</code>{{ t('dictionary.rules.listSeparator') }}<code class="mono">f.u.c.k</code>{{ t('dictionary.rules.sentenceEnd') }}
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><InfoFilled /></el-icon>
            <div>
              <b>{{ t('dictionary.rules.substring') }}</b>{{ t('dictionary.rules.substringLead') }}<code class="mono">unfuckingbelievable</code>{{ t('dictionary.rules.substringMatches') }}<code class="mono">fuck</code>{{ t('dictionary.rules.substringTail') }}
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><MagicStick /></el-icon>
            <div>
              <b>{{ t('dictionary.rules.minedDisabled') }}</b>{{ t('dictionary.rules.minedDisabledDesc') }}
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><WarningFilled /></el-icon>
            <div>
              <b>{{ t('dictionary.rules.saveTakesEffect') }}</b>{{ t('dictionary.rules.saveTakesEffectDesc') }}
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- 词条表 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters">
          <button class="filter-chip" :class="{ on: sourceFilter === '' && enabledFilter === null }" @click="clearFilters">
            {{ t('common.all') }}
          </button>
          <button class="filter-chip" :class="{ on: sourceFilter === 'MANUAL' }" @click="setSource('MANUAL')">
            {{ SOURCE_META.MANUAL.label }}
          </button>
          <button class="filter-chip" :class="{ on: sourceFilter === 'MINED' }" @click="setSource('MINED')">
            {{ SOURCE_META.MINED.label }}
          </button>
          <button class="filter-chip" :class="{ on: enabledFilter === false }" @click="filterEnabled(false)">
            {{ t('dictionary.filters.disabled') }}
          </button>
        </div>
        <div class="actions">
          <span v-if="!canWrite" class="chip info">{{ t('dictionary.readonly') }}</span>
          <el-input
            v-model="keyword"
            :placeholder="t('dictionary.searchPlaceholder')"
            clearable
            class="search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" :aria-label="t('common.refresh')" :title="t('common.refresh')" />
          <template v-if="canWrite">
            <el-button :icon="Download" :loading="exporting" @click="exportCsv">
              {{ t('dictionary.exportCsv') }}
            </el-button>
            <el-button :icon="Upload" @click="openImport">{{ t('dictionary.importCsv') }}</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('dictionary.createEntry') }}</el-button>
          </template>
        </div>
      </div>

      <!-- 批量启停（仅 ADMIN）：选中词条后出现 -->
      <div v-if="canWrite && selectedRows.length" class="bulk-bar">
        <span class="chip brand">{{ t('dictionary.bulk.selected', { count: selectedRows.length }) }}</span>
        <el-button
          size="small"
          type="success"
          plain
          :loading="bulkLoading === 'on'"
          @click="bulkUpdate(true)"
        >{{ t('dictionary.bulk.enable') }}</el-button>
        <el-popconfirm
          :title="t('dictionary.bulk.confirmDisable')"
          width="260"
          @confirm="bulkUpdate(false)"
        >
          <template #reference>
            <el-button size="small" type="warning" plain :loading="bulkLoading === 'off'">
              {{ t('dictionary.bulk.disable') }}
            </el-button>
          </template>
        </el-popconfirm>
        <el-button size="small" link @click="clearSelection">{{ t('dictionary.bulk.clear') }}</el-button>
        <span class="tiny dim">{{ t('dictionary.bulk.hint') }}</span>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column v-if="canWrite" type="selection" width="48" align="center" />
        <el-table-column :label="t('dictionary.table.word')" min-width="164">
          <template #default="{ row }">
            <div class="word-cell" :title="row.word">
              <span class="word mono">{{ row.word }}</span>
              <span
                v-if="row.source === 'MINED' && !row.enabled"
                class="chip warn"
                :title="t('dictionary.pendingReviewTip')"
              >{{ t('dictionary.pendingReview') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('dictionary.table.category')" width="138" align="center">
          <template #default="{ row }">
            <span v-if="row.category && VIOLATION_TYPES[row.category]" class="chip warn">
              {{ VIOLATION_TYPES[row.category] }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dictionary.table.severity')" width="114" align="center">
          <template #default="{ row }">
            <span class="chip" :class="SEVERITY_CLASS[row.severity]">
              <span class="dot" :style="{ background: SEVERITY_DOT[row.severity] }"></span>
              {{ SEVERITY_META[row.severity]?.label || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dictionary.table.source')" width="136" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.source === 'MINED' ? 'warn' : 'brand'">
              {{ SOURCE_META[row.source]?.label || row.source }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="!compactColumns" :label="t('dictionary.table.hits')" width="120">
          <template #default="{ row }">
            <div class="hits">
              <span class="num hits-value" :class="{ dim: !row.hitCount }">{{ row.hitCount }}</span>
              <!-- 命中为 0 时不画空轨道：一条灰线容易被误读成“加载中/占位” -->
              <span v-if="row.hitCount > 0" class="hits-track">
                <span class="hits-fill" :style="{ width: hitBarWidth(row.hitCount) }"></span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('dictionary.table.enabled')" width="94" align="center">
          <template #default="{ row }">
            <el-switch v-if="canWrite" :model-value="row.enabled" @change="(v) => toggle(row, v)" />
            <span v-else class="chip" :class="row.enabled ? 'ok' : 'info'">
              {{ row.enabled ? t('common.enabled') : t('common.disabled') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.updatedAt')" width="120">
          <template #default="{ row }">
            <span class="num dim" :title="formatTime(row.updatedAt || row.createdAt)">
              {{ formatTimeShort(row.updatedAt || row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" :label="t('common.actions')" width="104" align="right" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button size="small" link type="primary" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
              <el-popconfirm :title="t('dictionary.confirmDelete')" @confirm="remove(row)">
                <template #reference>
                  <el-button size="small" link type="danger">{{ t('common.delete') }}</el-button>
                </template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">{{ t('dictionary.empty.title') }}</div>
            <div class="tiny dim">{{ t('dictionary.empty.hint') }}</div>
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

    <!-- 导入 CSV（仅 ADMIN） -->
    <el-dialog v-model="importVisible" :title="t('dictionary.import.title')" width="560px" @closed="resetImport">
      <div class="import-hint">
        <div class="tip-line">
          <el-icon><InfoFilled /></el-icon>
          <span>
            {{ t('dictionary.import.columnsLead') }}<code class="mono">word,category,severity,enabled</code>{{ t('dictionary.import.columnsRest') }}
          </span>
        </div>
        <div class="tip-line">
          <el-icon><InfoFilled /></el-icon>
          <span>
            {{ t('dictionary.import.valueSyntax') }}
          </span>
        </div>
      </div>

      <el-upload
        ref="importUploadRef"
        class="import-uploader"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".csv,text/csv"
        :on-change="onImportFileChange"
        :on-remove="onImportFileRemove"
        :on-exceed="onImportExceed"
      >
        <el-icon class="el-icon--upload"><Upload /></el-icon>
        <div class="el-upload__text">{{ t('dictionary.import.dragPrefix') }}<em>{{ t('dictionary.import.clickSelect') }}</em></div>
        <template #tip>
          <div class="el-upload__tip">{{ t('dictionary.import.uploadTip') }}</div>
        </template>
      </el-upload>

      <div v-if="importResult" class="import-result">
        <div class="result-item ok">
          <span class="result-value num">{{ importResult.created }}</span>
          <span class="result-label">{{ t('dictionary.import.created') }}</span>
        </div>
        <div class="result-item info">
          <span class="result-value num">{{ importResult.skipped }}</span>
          <span class="result-label">{{ t('dictionary.import.skipped') }}</span>
        </div>
        <div class="result-item danger">
          <span class="result-value num">{{ importResult.invalid }}</span>
          <span class="result-label">{{ t('dictionary.import.invalid') }}</span>
        </div>
      </div>

      <template #footer>
        <el-button @click="importVisible = false">{{ importResult ? t('dictionary.import.done') : t('common.cancel') }}</el-button>
        <el-button
          v-if="!importResult"
          type="primary"
          :loading="importing"
          :disabled="!importFile"
          @click="submitImport"
        >{{ t('dictionary.import.start') }}</el-button>
        <el-button v-else type="primary" :disabled="importing" @click="resetImportForm">
          {{ t('dictionary.import.continue') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('dictionary.dialog.editTitle') : t('dictionary.dialog.createTitle')"
      width="470px"
    >
      <el-form :model="form" label-width="82px">
        <el-form-item :label="t('dictionary.table.word')">
          <el-input v-model="form.word" :placeholder="t('dictionary.form.wordPlaceholder')" />
          <div class="norm-hint">
            {{ t('dictionary.form.normalized') }}<code class="mono">{{ normalizedPreview || '—' }}</code>
            <span class="dim">{{ t('dictionary.form.normalizedHint') }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('dictionary.table.category')">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="(label, code) in VIOLATION_TYPES" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('dictionary.table.severity')">
          <div class="sev-grid">
            <button
              v-for="(meta, code) in SEVERITY_META"
              :key="code"
              class="type-chip"
              :class="{ on: form.severity === code }"
              @click="form.severity = code"
            >{{ meta.label }}</button>
          </div>
        </el-form-item>
        <el-form-item :label="t('common.enabled')">
          <el-switch v-model="form.enabled" />
          <span class="dim tiny ml-8">{{ t('dictionary.form.enabledHint') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ t('dictionary.form.saveAndRebuild') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus, MagicStick, Download, Upload } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import StatCard from '../components/StatCard.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import { VIOLATION_TYPES, SEVERITY_META, SOURCE_META, formatTime, formatTimeShort } from '../constants'
import { saveBlob } from '../utils/download'
import { useElementWidth } from '../utils/elementWidth'
import { useI18n } from '../i18n'

const route = useRoute()
const { t } = useI18n()

// 敏感词写操作仅 ADMIN：非管理员隐藏入口（后端仍会独立校验）
const canWrite = computed(() => auth.hasRole('ADMIN'))

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 15
const loading = ref(false)
const keyword = ref('')
const sourceFilter = ref('')
const enabledFilter = ref(null)
const overview = ref(null)

const testText = ref('')
const testHits = ref([])
const tested = ref(false)
const testing = ref(false)

const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ word: '', category: 'INSULT', severity: 'MEDIUM', enabled: true })

// 表格多选 + 批量启停（仅 ADMIN）
const tableRef = ref(null)
/* 窄屏收起「命中次数」，避免固定操作列压住「更新时间」 */
const tableWidth = useElementWidth(tableRef)
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 930)
const selectedRows = ref([])
const bulkLoading = ref('')

// 导出 / 导入（仅 ADMIN）
const exporting = ref(false)
const importVisible = ref(false)
const importing = ref(false)
const importUploadRef = ref(null)
const importFile = ref(null)
const importResult = ref(null)

const SEVERITY_DOT = { HIGH: '#ef4444', MEDIUM: '#f59e0b', LOW: '#94a3b8' }
const SEVERITY_CLASS = { HIGH: 'danger', MEDIUM: 'warn', LOW: 'info' }

const stats = computed(() => ({
  total: overview.value?.dictionary?.total ?? total.value,
  enabled: overview.value?.dictionary?.enabled ?? 0,
  minedPending: overview.value?.dictionary?.minedPending ?? 0
}))

const maxHits = computed(() => Math.max(1, ...rows.value.map((r) => r.hitCount || 0)))

const normalizedPreview = computed(() => normalizeWord(form.value.word))

function normalizeWord(w) {
  return (w || '')
    .toLowerCase()
    .split('')
    .filter((c) => /[a-z0-9]/.test(c) || c.charCodeAt(0) > 127)
    .join('')
}

function hitBarWidth(count) {
  return Math.max(2, Math.round(((count || 0) / maxHits.value) * 100)) + '%'
}

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
    const data = await api.listDictionary({
      keyword: keyword.value || undefined,
      source: sourceFilter.value || undefined,
      enabled: enabledFilter.value ?? undefined,
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

function clearFilters() {
  sourceFilter.value = ''
  enabledFilter.value = null
  keyword.value = ''
  reload()
}
function setSource(v) {
  sourceFilter.value = sourceFilter.value === v ? '' : v
  reload()
}
function filterEnabled(v) {
  enabledFilter.value = enabledFilter.value === v ? null : v
  reload()
}
function filterMined() {
  sourceFilter.value = 'MINED'
  enabledFilter.value = false
  reload()
}

function openCreate() {
  editing.value = null
  form.value = { word: '', category: 'INSULT', severity: 'MEDIUM', enabled: true }
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.value = {
    word: row.word,
    category: row.category || 'INSULT',
    severity: row.severity || 'MEDIUM',
    enabled: row.enabled
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.word?.trim()) {
    ElMessage.warning(t('dictionary.messages.wordRequired'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateWord(editing.value.id, form.value)
      ElMessage.success(t('dictionary.messages.updated'))
    } else {
      await api.createWord(form.value)
      ElMessage.success(t('dictionary.messages.created'))
    }
    dialogVisible.value = false
    reload()
    loadOverview()
  } catch {
    // 403 等由请求层统一提示
  } finally {
    saving.value = false
  }
}

async function toggle(row, v) {
  try {
    await api.updateWord(row.id, { enabled: v })
    ElMessage.success(
      v
        ? t('dictionary.messages.enabledWord', { word: row.word })
        : t('dictionary.messages.disabledWord', { word: row.word })
    )
    load()
    loadOverview()
  } catch {
    // 403 等由请求层统一提示
  }
}

async function remove(row) {
  try {
    await api.deleteWord(row.id)
    ElMessage.success(t('dictionary.messages.deleted'))
    load()
    loadOverview()
  } catch {
    // 403 等由请求层统一提示
  }
}

/* ---------- 导出 / 导入 / 批量启停（仅 ADMIN） ---------- */

function onSelectionChange(selection) {
  selectedRows.value = Array.isArray(selection) ? selection.slice() : []
}

function clearSelection() {
  selectedRows.value = []
  tableRef.value?.clearSelection?.()
}

/** 批量启用/停用：后端重建 DFA，返回 { updated } */
async function bulkUpdate(enabled) {
  if (!canWrite.value) return
  const ids = selectedRows.value.map((r) => r.id).filter((id) => id != null)
  if (!ids.length) return
  bulkLoading.value = enabled ? 'on' : 'off'
  try {
    const r = await api.bulkEnabled(ids, enabled)
    const count = Number(r?.updated) || ids.length
    ElMessage.success(
      t(enabled ? 'dictionary.messages.bulkEnabled' : 'dictionary.messages.bulkDisabled', { count })
    )
    clearSelection()
    load()
    loadOverview()
  } catch {
    // 403 等由请求层统一提示
  } finally {
    bulkLoading.value = ''
  }
}

async function exportCsv() {
  if (!canWrite.value) return
  exporting.value = true
  try {
    const { blob, filename } = await api.exportDictionaryCsv()
    saveBlob(blob, filename)
    ElMessage.success(t('dictionary.messages.exported', { filename }))
  } catch {
    // 请求层 / 接口层已统一提示
  } finally {
    exporting.value = false
  }
}

function openImport() {
  if (!canWrite.value) return
  resetImportForm()
  importVisible.value = true
}

function resetImportForm() {
  importFile.value = null
  importResult.value = null
  importUploadRef.value?.clearFiles?.()
}

function resetImport() {
  resetImportForm()
  importing.value = false
}

function onImportFileChange(file) {
  const raw = file?.raw
  if (!raw) return
  if (!/\.csv$/i.test(raw.name || '')) {
    ElMessage.error(t('dictionary.messages.csvRequired'))
    importUploadRef.value?.clearFiles?.()
    return
  }
  if (raw.size > 5 * 1024 * 1024) {
    ElMessage.error(t('dictionary.messages.csvTooLarge'))
    importUploadRef.value?.clearFiles?.()
    return
  }
  importFile.value = raw
  importResult.value = null
}

function onImportFileRemove() {
  importFile.value = null
}

/** 已选一个再选：替换而不是报错 */
function onImportExceed(files) {
  const file = files?.[0]
  if (!file) return
  importUploadRef.value?.clearFiles?.()
  onImportFileChange({ raw: file })
  importUploadRef.value?.handleStart?.(file)
}

async function submitImport() {
  if (!importFile.value) {
    ElMessage.warning(t('dictionary.messages.importFileRequired'))
    return
  }
  importing.value = true
  try {
    const r = await api.importDictionary(importFile.value)
    importResult.value = {
      created: Number(r?.created) || 0,
      skipped: Number(r?.skipped) || 0,
      invalid: Number(r?.invalid) || 0
    }
    ElMessage.success(
      t('dictionary.messages.importDone', {
        created: importResult.value.created,
        skipped: importResult.value.skipped,
        invalid: importResult.value.invalid
      })
    )
    reload()
    loadOverview()
  } catch {
    // 403 / 校验失败由请求层统一提示，保留文件便于重试
  } finally {
    importing.value = false
  }
}

async function runTest() {
  if (!testText.value?.trim()) {
    ElMessage.warning(t('dictionary.messages.testTextRequired'))
    return
  }
  testing.value = true
  try {
    testHits.value = await api.testText(testText.value)
    tested.value = true
  } finally {
    testing.value = false
  }
}

function clearTest() {
  testText.value = ''
  testHits.value = []
  tested.value = false
}

onMounted(() => {
  if (route.query.source) sourceFilter.value = route.query.source
  if (route.query.enabled === 'false') enabledFilter.value = false
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

/* 检测工具 + 说明：同一行内等高，工具卡 textarea 吸收剩余高度 */
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}
.two-col > .panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.two-col > .panel > .panel-body {
  flex: 1;
  min-height: 0;
}
.dfa-panel .panel-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dfa-field {
  display: flex;
  flex: 1 1 auto;
  min-height: 98px;
}
.dfa-field :deep(.el-textarea) {
  display: flex;
  flex: 1;
}
.dfa-field :deep(.el-textarea__inner) {
  height: 100%;
  min-height: 98px;
  resize: none;
}
.dfa-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex: none;
}
/* 1280 笔记本宽度下 DFA 工具与规则说明仍并排；低于 1200 再堆叠 */
@media (max-width: 1200px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
  .dfa-field,
  .dfa-field :deep(.el-textarea__inner) {
    min-height: 86px;
  }
}

.ml-8 {
  margin-left: 8px;
}

.preview {
  border-top: 1px dashed var(--border);
  padding-top: 12px;
}
.preview-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.tips {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tip {
  display: flex;
  gap: 9px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--el-text-color-regular);
}
.tip-icon {
  color: var(--brand-500);
  margin-top: 3px;
  flex: none;
}
.tip code {
  background: var(--ink-100);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 11.5px;
  color: var(--brand-700);
  /* 内联代码不折行：避免「F U / C K」这种词中间断行打断阅读 */
  white-space: nowrap;
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
  gap: 6px;
  flex-wrap: wrap;
}
.filter-chip {
  height: 30px;
  padding: 0 13px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.filter-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.filter-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
  box-shadow: 0 4px 12px rgba(59, 110, 246, 0.24);
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  width: 210px;
}

.word-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.word {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  background: var(--ink-50);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 8px;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hits {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.hits-value {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-700);
  min-width: 22px;
}
.hits-track {
  flex: 1;
  min-width: 0;
  height: 6px;
  border-radius: 999px;
  background: var(--ink-100);
  overflow: hidden;
}
.hits-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #f59e0b, #ef4444);
}

/* 操作列：链接按钮统一 gap，避免 18px 内边距下折行 */
.op-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  white-space: nowrap;
}
.op-cell :deep(.el-button + .el-button) {
  margin-left: 0;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}

.norm-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sev-grid {
  display: flex;
  gap: 6px;
}
.type-chip {
  height: 28px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
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
.readonly-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 4px 0 2px;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
  line-height: 1.7;
}

/* 批量启停条 */
.bulk-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 18px;
  background: var(--brand-50);
  border-bottom: 1px solid var(--border);
}

/* 导入 CSV */
.import-hint {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.65;
}
.tip-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
.tip-line .el-icon {
  margin-top: 2px;
  flex: none;
  color: var(--brand-500);
}
.tip-line code {
  color: var(--brand-700);
  background: var(--brand-50);
  border-radius: 4px;
  padding: 0 4px;
  white-space: nowrap;
}
.import-uploader {
  width: 100%;
}
.import-uploader :deep(.el-upload),
.import-uploader :deep(.el-upload-dragger) {
  width: 100%;
}
.import-uploader :deep(.el-upload-dragger) {
  padding: 20px 12px;
}
.import-result {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.result-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  border: 1px solid var(--border);
  background: #fbfcfe;
}
.result-item.ok {
  border-color: #cdebd8;
  background: var(--ok-soft);
}
.result-item.info {
  background: var(--ink-50);
}
.result-item.danger {
  border-color: #f8d4d4;
  background: var(--danger-soft);
}
.result-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink-800);
}
.result-item.ok .result-value {
  color: #12703a;
}
.result-item.danger .result-value {
  color: #c92c2c;
}
.result-label {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
}
</style>
