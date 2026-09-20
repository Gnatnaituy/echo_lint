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
          <el-input v-model="keyword" placeholder="搜索词条" clearable class="search" @keyup.enter="reload" @clear="reload">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" />
          <el-button type="primary" :icon="Plus" @click="openCreate">新增词条</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows">
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
            <el-switch :model-value="row.enabled" @change="(v) => toggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.updatedAt || row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="right">
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
import { Search, Refresh, Plus, MagicStick } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import { VIOLATION_TYPES, SEVERITY_META, SOURCE_META, formatTimeShort } from '../constants'

const route = useRoute()

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
  } finally {
    saving.value = false
  }
}

async function toggle(row, v) {
  await api.updateWord(row.id, { enabled: v })
  ElMessage.success(v ? `已启用「${row.word}」` : `已停用「${row.word}」`)
  load()
  loadOverview()
}

async function remove(row) {
  await api.deleteWord(row.id)
  ElMessage.success('已删除')
  load()
  loadOverview()
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
</style>
