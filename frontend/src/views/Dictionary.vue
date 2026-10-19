<template>
  <div class="page stack">
    <!-- 概览 -->
    <div class="kpi-grid">
      <StatCard
        label="词条总数"
        :value="stats.total"
        icon="Collection"
        tone="brand"
        :sub="`启用中 ${stats.enabled} 条`"
        clickable
        :active="!sourceFilter && !enabledFilter"
        @click="clearFilters"
      />
      <StatCard
        label="启用中"
        :value="stats.enabled"
        icon="Select"
        tone="ok"
        sub="参与 DFA 初筛"
        clickable
        :active="enabledFilter === true"
        @click="filterEnabled(true)"
      />
      <StatCard
        label="AI 挖掘待审核"
        :value="stats.minedPending"
        icon="MagicStick"
        tone="warn"
        sub="来自确认违规录音"
        clickable
        :active="sourceFilter === 'MINED' && enabledFilter === false"
        @click="filterMined"
      />
    </div>

    <!-- 检测工具 + 使用说明 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">DFA 检测工具</div>
          <span class="tiny dim">使用当前启用词典实时匹配</span>
        </div>
        <div class="panel-body">
          <template v-if="canWrite">
            <el-input
              v-model="testText"
              type="textarea"
              :rows="3"
              placeholder="粘贴一段转写文本，例如：you are a f***ing idiot, I will kill you!"
            />
            <div class="row mt-10">
              <el-button type="primary" :icon="MagicStick" :loading="testing" @click="runTest">检测</el-button>
              <el-button v-if="tested" link @click="clearTest">清空</el-button>
              <span v-if="tested && testHits.length" class="chip danger">命中 {{ testHits.length }} 处</span>
              <span v-else-if="tested" class="chip ok">未命中，文本干净</span>
            </div>

            <div v-if="tested && testText" class="preview">
              <div class="preview-label">命中预览</div>
              <TranscriptViewer :text="testText" :hits="testHits" />
            </div>
          </template>
          <div v-else class="readonly-hint">
            <el-icon :size="15"><Lock /></el-icon>
            <span>只读角色可浏览词库与统计；DFA 检测工具与词条维护仅管理员可用。</span>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">匹配规则与维护说明</div>
        </div>
        <div class="panel-body tips">
          <div class="tip">
            <el-icon class="tip-icon"><InfoFilled /></el-icon>
            <div>
              <b>不区分大小写</b>，且忽略词间空格与标点：词典里的 <code class="mono">fuck</code>
              能命中 <code class="mono">F U C K</code>、<code class="mono">f.u.c.k</code>。
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><InfoFilled /></el-icon>
            <div>
              <b>子串语义</b>：<code class="mono">unfuckingbelievable</code> 会命中 <code class="mono">fuck</code>。
              命中的误报由 AI 语义复筛兜底，这是两段式设计的取舍。
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><MagicStick /></el-icon>
            <div>
              <b>AI 挖掘的词默认停用</b>：人工复检确认违规后，系统会提炼新词入库，需在此页审核后启用。
            </div>
          </div>
          <div class="tip">
            <el-icon class="tip-icon"><WarningFilled /></el-icon>
            <div>
              <b>保存即生效</b>：任何增删改都会重建 DFA 匹配器，无需重启服务。
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
            全部
          </button>
          <button class="filter-chip" :class="{ on: sourceFilter === 'MANUAL' }" @click="setSource('MANUAL')">
            人工录入
          </button>
          <button class="filter-chip" :class="{ on: sourceFilter === 'MINED' }" @click="setSource('MINED')">
            AI 挖掘
          </button>
          <button class="filter-chip" :class="{ on: enabledFilter === false }" @click="filterEnabled(false)">
            已停用
          </button>
        </div>
        <div class="actions">
          <span v-if="!canWrite" class="chip info">只读模式</span>
          <el-input v-model="keyword" placeholder="搜索词条" clearable class="search" @keyup.enter="reload" @clear="reload">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" aria-label="刷新" title="刷新" />
          <template v-if="canWrite">
            <el-button :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
            <el-button :icon="Upload" @click="openImport">导入 CSV</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新增词条</el-button>
          </template>
        </div>
      </div>

      <!-- 批量启停（仅 ADMIN）：选中词条后出现 -->
      <div v-if="canWrite && selectedRows.length" class="bulk-bar">
        <span class="chip brand">已选 {{ selectedRows.length }} 条</span>
        <el-button
          size="small"
          type="success"
          plain
          :loading="bulkLoading === 'on'"
          @click="bulkUpdate(true)"
        >批量启用</el-button>
        <el-popconfirm
          title="确认停用选中词条？停用后不再参与 DFA 初筛。"
          width="260"
          @confirm="bulkUpdate(false)"
        >
          <template #reference>
            <el-button size="small" type="warning" plain :loading="bulkLoading === 'off'">
              批量停用
            </el-button>
          </template>
        </el-popconfirm>
        <el-button size="small" link @click="clearSelection">取消选择</el-button>
        <span class="tiny dim">启停后立即重建 DFA 匹配器</span>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column v-if="canWrite" type="selection" width="44" align="center" />
        <el-table-column label="敏感词" min-width="180">
          <template #default="{ row }">
            <div class="word-cell">
              <span class="word mono">{{ row.word }}</span>
              <span
                v-if="row.source === 'MINED' && !row.enabled"
                class="chip warn"
                title="AI 从确认违规录音中挖掘，启用前请人工审核"
              >待审核</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="违规类型" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.category && VIOLATION_TYPES[row.category]" class="chip warn">
              {{ VIOLATION_TYPES[row.category] }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="严重度" width="96" align="center">
          <template #default="{ row }">
            <span class="chip" :class="SEVERITY_CLASS[row.severity]">
              <span class="dot" :style="{ background: SEVERITY_DOT[row.severity] }"></span>
              {{ SEVERITY_META[row.severity]?.label || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="112" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.source === 'MINED' ? 'warn' : 'brand'">
              {{ SOURCE_META[row.source]?.label || row.source }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="命中次数" width="140">
          <template #default="{ row }">
            <div class="hits">
              <span class="num hits-value" :class="{ dim: !row.hitCount }">{{ row.hitCount }}</span>
              <span class="hits-track">
                <span v-if="row.hitCount > 0" class="hits-fill" :style="{ width: hitBarWidth(row.hitCount) }"></span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="84" align="center">
          <template #default="{ row }">
            <el-switch v-if="canWrite" :model-value="row.enabled" @change="(v) => toggle(row, v)" />
            <span v-else class="chip" :class="row.enabled ? 'ok' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.updatedAt || row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="110" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除该词？" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">没有匹配的词条</div>
            <div class="tiny dim">调整筛选条件，或新增一个敏感词</div>
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
    <el-dialog v-model="importVisible" title="导入敏感词 CSV" width="560px" @closed="resetImport">
      <div class="import-hint">
        <div class="tip-line">
          <el-icon><InfoFilled /></el-icon>
          <span>
            CSV 列：<code class="mono">word,category,severity,enabled</code>，允许表头，最多 5000 行；规范化后自动去重。
          </span>
        </div>
        <div class="tip-line">
          <el-icon><InfoFilled /></el-icon>
          <span>
            category：INSULT / DISCRIMINATION / THREAT / HARASSMENT / SEXUAL / FRAUD / PRIVACY / OTHER；
            severity：HIGH / MEDIUM / LOW；enabled：true / false。
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
        <div class="el-upload__text">拖拽 CSV 到此处，或 <em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">单文件 ≤ 5MB；导入成功后自动重建 DFA 匹配器</div>
        </template>
      </el-upload>

      <div v-if="importResult" class="import-result">
        <div class="result-item ok">
          <span class="result-value num">{{ importResult.created }}</span>
          <span class="result-label">新增</span>
        </div>
        <div class="result-item info">
          <span class="result-value num">{{ importResult.skipped }}</span>
          <span class="result-label">跳过（重复/已存在）</span>
        </div>
        <div class="result-item danger">
          <span class="result-value num">{{ importResult.invalid }}</span>
          <span class="result-label">格式错误</span>
        </div>
      </div>

      <template #footer>
        <el-button @click="importVisible = false">{{ importResult ? '完成' : '取消' }}</el-button>
        <el-button
          v-if="!importResult"
          type="primary"
          :loading="importing"
          :disabled="!importFile"
          @click="submitImport"
        >开始导入</el-button>
        <el-button v-else type="primary" :disabled="importing" @click="resetImportForm">
          继续导入
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑敏感词' : '新增敏感词'" width="470px">
      <el-form :model="form" label-width="82px">
        <el-form-item label="敏感词">
          <el-input v-model="form.word" placeholder="如 kill you / 傻逼" />
          <div class="norm-hint">
            规范化结果：<code class="mono">{{ normalizedPreview || '—' }}</code>
            <span class="dim">（小写、去空格与标点）</span>
          </div>
        </el-form-item>
        <el-form-item label="违规类型">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="(label, code) in VIOLATION_TYPES" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
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
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="dim tiny ml-8">停用的词不参与初筛</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并重建匹配器</el-button>
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
import { VIOLATION_TYPES, SEVERITY_META, SOURCE_META, formatTimeShort } from '../constants'
import { saveBlob } from '../utils/download'

const route = useRoute()

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
    ElMessage.warning('请输入敏感词')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateWord(editing.value.id, form.value)
      ElMessage.success('已更新，DFA 匹配器已重建')
    } else {
      await api.createWord(form.value)
      ElMessage.success('已新增，DFA 匹配器已重建')
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
    ElMessage.success(v ? `已启用「${row.word}」` : `已停用「${row.word}」`)
    load()
    loadOverview()
  } catch {
    // 403 等由请求层统一提示
  }
}

async function remove(row) {
  try {
    await api.deleteWord(row.id)
    ElMessage.success('已删除')
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
    ElMessage.success(`已${enabled ? '启用' : '停用'} ${count} 条，DFA 匹配器已重建`)
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
    ElMessage.success(`词库已导出：${filename}`)
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
    ElMessage.error('请选择 .csv 文件')
    importUploadRef.value?.clearFiles?.()
    return
  }
  if (raw.size > 5 * 1024 * 1024) {
    ElMessage.error('CSV 文件超过 5MB')
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
    ElMessage.warning('请选择要导入的 CSV 文件')
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
      `导入完成：新增 ${importResult.value.created}，跳过 ${importResult.value.skipped}，无效 ${importResult.value.invalid}`
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
    ElMessage.warning('请输入检测文本')
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
  gap: 14px;
}
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  gap: 16px;
}
@media (max-width: 1280px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
}

.mt-10 {
  margin-top: 10px;
}
.ml-8 {
  margin-left: 8px;
}

.preview {
  margin-top: 14px;
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
}
.search {
  width: 210px;
}

.word-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.word {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  background: var(--ink-50);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px 8px;
}

.hits {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hits-value {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-700);
  min-width: 22px;
}
.hits-track {
  flex: 1;
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

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
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
