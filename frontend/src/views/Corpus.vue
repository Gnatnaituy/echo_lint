<template>
  <div class="page stack">
    <!-- 概览 -->
    <div class="kpi-grid">
      <StatCard
        label="语料总数"
        :value="(stats.violation || 0) + (stats.compliant || 0)"
        icon="Notebook"
        tone="brand"
        sub="人工复检自动沉淀"
      />
      <StatCard
        label="违规样本"
        :value="stats.violation"
        icon="WarningFilled"
        tone="danger"
        sub="few-shot 正例"
        clickable
        :active="labelFilter === 'VIOLATION'"
        @click="setLabel('VIOLATION')"
      />
      <StatCard
        label="合规样本"
        :value="stats.compliant"
        icon="CircleCheckFilled"
        tone="ok"
        sub="few-shot 反例"
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
          <div class="loop-title">人工复检 → 语料库 → AI 复筛 的自增强闭环</div>
          <div class="loop-steps">
            <span class="step"><b>1</b> 人工复检提交结论，自动写入语料库</span>
            <span class="step"><b>2</b> AI 复筛时取最新违规 / 合规样例作为 few-shot 提示</span>
            <span class="step"><b>3</b> 确认违规的录音另由 AI 挖掘新敏感词（停用待审核）</span>
          </div>
        </div>
        <el-button :icon="Download" @click="doExport">导出 JSON</el-button>
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters">
          <button class="filter-chip" :class="{ on: labelFilter === '' }" @click="setLabel('')">
            全部
          </button>
          <button class="filter-chip" :class="{ on: labelFilter === 'VIOLATION' }" @click="setLabel('VIOLATION')">
            违规样本
          </button>
          <button class="filter-chip" :class="{ on: labelFilter === 'COMPLIANT' }" @click="setLabel('COMPLIANT')">
            合规样本
          </button>
        </div>
        <div class="actions">
          <el-input v-model="keyword" placeholder="搜索语料文本 / 理由" clearable class="search" @keyup.enter="reload" @clear="reload">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" />
          <el-button type="primary" :icon="Plus" @click="openCreate">新增语料</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows">
        <el-table-column label="标注" width="96" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.label === 'VIOLATION' ? 'danger' : 'ok'">
              {{ row.label === 'VIOLATION' ? '违规' : '合规' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="违规类型" width="116" align="center">
          <template #default="{ row }">
            <span v-if="row.violationType && VIOLATION_TYPES[row.violationType]" class="chip warn">
              {{ VIOLATION_TYPES[row.violationType] }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="语料文本" min-width="330">
          <template #default="{ row }">
            <el-tooltip :content="row.transcript" placement="top" :show-after="400">
              <div class="corpus-text">{{ row.transcript }}</div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="标注理由" min-width="180">
          <template #default="{ row }">
            <span :class="{ dim: !row.reason }" class="reason-text">{{ row.reason || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="112" align="center">
          <template #default="{ row }">
            <span class="chip" :class="SOURCE_CLASS[row.source] || 'info'">
              {{ SOURCE_LABEL[row.source] || row.source }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="num dim">{{ formatTimeShort(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="86" align="right">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该条语料？" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">暂无语料</div>
            <div class="tiny dim">完成人工复检后，结论会自动沉淀到这里</div>
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
    <el-dialog v-model="dialogVisible" title="新增语料" width="560px">
      <el-form :model="form" label-width="82px">
        <el-form-item label="标注">
          <div class="seg">
            <button
              class="seg-item"
              :class="{ on: form.label === 'VIOLATION' }"
              @click="form.label = 'VIOLATION'"
            >违规样本</button>
            <button
              class="seg-item"
              :class="{ on: form.label === 'COMPLIANT' }"
              @click="form.label = 'COMPLIANT'"
            >合规样本</button>
          </div>
        </el-form-item>
        <el-form-item v-if="form.label === 'VIOLATION'" label="违规类型">
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
        <el-form-item label="语料文本">
          <el-input v-model="form.transcript" type="textarea" :rows="4" placeholder="粘贴一段客服录音转写文本" />
        </el-form-item>
        <el-form-item label="标注理由">
          <el-input v-model="form.reason" placeholder="为什么这么判（可选，作为 AI 参考依据）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus, Download } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import { VIOLATION_TYPES, formatTimeShort } from '../constants'

const rows = ref([])
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

const SOURCE_LABEL = { REVIEW: '人工复检', SEED: '初始合成', MANUAL: '手工录入' }
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
    ElMessage.warning('请输入语料文本')
    return
  }
  saving.value = true
  try {
    await api.createCorpus(form.value)
    ElMessage.success('已新增语料')
    dialogVisible.value = false
    reload()
    loadOverview()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await api.deleteCorpus(row.id)
  ElMessage.success('已删除')
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
    ElMessage.error('导出失败')
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
  gap: 14px;
}

/* 闭环说明 */
.loop-body {
  display: flex;
  align-items: center;
  gap: 14px;
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
  flex: 1;
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
  gap: 6px;
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
  width: 230px;
}

.corpus-text {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.reason-text {
  font-size: 12.5px;
  line-height: 1.6;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
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
