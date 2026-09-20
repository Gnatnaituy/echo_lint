<template>
  <div>
    <el-card shadow="never" class="mb-16">
      <div class="toolbar-row">
        <el-input v-model="keyword" placeholder="搜索敏感词" clearable style="width: 220px" @keyup.enter="reload" @clear="reload">
          <template #append><el-button :icon="Search" @click="reload" /></template>
        </el-input>
        <el-select v-model="sourceFilter" placeholder="来源" clearable style="width: 130px" @change="reload">
          <el-option label="人工录入" value="MANUAL" />
          <el-option label="AI 挖掘" value="MINED" />
        </el-select>
        <el-select v-model="enabledFilter" placeholder="启用状态" clearable style="width: 130px" @change="reload">
          <el-option label="启用中" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
        <div class="flex-1" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新增敏感词</el-button>
      </div>
      <div class="toolbar-tip">
        DFA 匹配器不区分大小写，且忽略词间空格/标点（"f u c k" 也能命中 "fuck"）；命中仅作初筛，误报由 AI 复筛兜底。
      </div>
    </el-card>

    <el-card shadow="never" class="mb-16">
      <template #header>
        <div class="card-head">
          <span class="card-head-title">检测工具</span>
          <span class="muted">粘贴一段文本，实时查看当前启用词典的命中情况</span>
        </div>
      </template>
      <el-input v-model="testText" type="textarea" :rows="3" placeholder="例如: you are a f**king idiot, I will kill you!" />
      <div class="toolbar-row mt-8">
        <el-button type="primary" :icon="MagicStick" :loading="testing" @click="runTest">检测</el-button>
        <template v-if="testHits.length">
          <el-tag v-for="(h, i) in testHits" :key="i" type="danger" size="small" class="hit-tag">
            {{ h.word }} [{{ h.start }}, {{ h.end }})
          </el-tag>
          <span class="muted">共 {{ testHits.length }} 处命中</span>
        </template>
        <span v-else-if="tested" class="muted">未命中任何启用词</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="暂无敏感词">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="word" label="敏感词" min-width="160">
          <template #default="{ row }"><b>{{ row.word }}</b></template>
        </el-table-column>
        <el-table-column label="类别" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.category && VIOLATION_TYPES[row.category]" type="warning" size="small" effect="plain">
              {{ VIOLATION_TYPES[row.category] }}
            </el-tag>
            <span v-else class="muted">{{ row.category || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="严重度" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="SEVERITY_META[row.severity]?.type" size="small">{{ SEVERITY_META[row.severity]?.label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="SOURCE_META[row.source]?.type" size="small" effect="plain">{{ SOURCE_META[row.source]?.label }}</el-tag>
            <el-tooltip
              v-if="row.source === 'MINED' && !row.enabled"
              content="AI 从确认违规录音中挖掘，启用前请人工审核"
              placement="top"
            >
              <el-icon class="tip-icon" color="#e6a23c"><Warning /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v) => toggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" label="累计命中" width="90" align="center" />
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除该词？" @confirm="remove(row)">
              <template #reference>
                <el-button size="small" link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
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
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑敏感词' : '新增敏感词'" width="460px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="敏感词">
          <el-input v-model="form.word" placeholder="如 kill you / 傻逼" />
          <div class="muted small">保存时自动规范化：小写、去空格与标点</div>
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="(label, code) in VIOLATION_TYPES" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option label="高" value="HIGH" />
            <el-option label="中" value="MEDIUM" />
            <el-option label="低" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Plus, MagicStick, Warning } from '@element-plus/icons-vue'
import api from '../api'
import { VIOLATION_TYPES, SEVERITY_META, SOURCE_META } from '../constants'

const route = useRoute()

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 15
const loading = ref(false)
const keyword = ref('')
const sourceFilter = ref('')
const enabledFilter = ref(null)

const testText = ref('')
const testHits = ref([])
const tested = ref(false)
const testing = ref(false)

const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ word: '', category: 'INSULT', severity: 'MEDIUM', enabled: true })

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

function openCreate() {
  editing.value = null
  form.value = { word: '', category: 'INSULT', severity: 'MEDIUM', enabled: true }
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.value = { word: row.word, category: row.category, severity: row.severity, enabled: row.enabled }
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
  } finally {
    saving.value = false
  }
}

async function toggle(row, v) {
  await api.updateWord(row.id, { enabled: v })
  row.enabled = v
  ElMessage.success(v ? '已启用' : '已停用')
  load()
}

async function remove(row) {
  await api.deleteWord(row.id)
  ElMessage.success('已删除')
  reload()
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

onMounted(() => {
  if (route.query.source) sourceFilter.value = route.query.source
  if (route.query.enabled !== undefined) enabledFilter.value = route.query.enabled === 'false' ? false : true
  reload()
})
</script>

<style scoped>
.toolbar-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.flex-1 {
  flex: 1;
}
.toolbar-tip {
  margin-top: 10px;
  color: #909399;
  font-size: 12px;
}
.mt-8 {
  margin-top: 8px;
}
.mb-16 {
  margin-bottom: 16px;
}
.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.card-head-title {
  font-weight: 600;
}
.hit-tag {
  margin-right: 6px;
}
.tip-icon {
  vertical-align: middle;
  margin-left: 4px;
}
.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
.small {
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.6;
}
</style>