<template>
  <div>
    <el-card shadow="never" class="mb-16">
      <div class="toolbar-row">
        <el-select v-model="labelFilter" placeholder="标注" clearable style="width: 140px" @change="reload">
          <el-option label="违规样本" value="VIOLATION" />
          <el-option label="合规样本" value="COMPLIANT" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索语料文本" clearable style="width: 240px" @keyup.enter="reload" @clear="reload">
          <template #append><el-button :icon="Search" @click="reload" /></template>
        </el-input>
        <div class="flex-1" />
        <el-button :icon="Download" @click="doExport">导出 JSON</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增语料</el-button>
      </div>
      <div class="toolbar-tip">
        语料由<b>人工复检结论自动回馈</b>：复筛时取最新的违规/合规样例注入 few-shot 提示词，让 AI 判断越来越贴合人工标准。
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="暂无语料，人工复检后自动沉淀">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="标注" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.label === 'VIOLATION' ? 'danger' : 'success'" size="small">
              {{ row.label === 'VIOLATION' ? '违规' : '合规' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.violationType && VIOLATION_TYPES[row.violationType]" type="warning" size="small" effect="plain">
              {{ VIOLATION_TYPES[row.violationType] }}
            </el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="transcript" label="语料文本" min-width="320" show-overflow-tooltip />
        <el-table-column prop="reason" label="标注理由" min-width="180" show-overflow-tooltip />
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.source === 'SEED' ? 'info' : 'primary'">
              {{ row.source === 'REVIEW' ? '人工复检' : row.source === 'SEED' ? '初始合成' : '手工录入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该语料？" @confirm="remove(row)">
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

    <!-- 新增语料 -->
    <el-dialog v-model="dialogVisible" title="新增语料" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标注">
          <el-radio-group v-model="form.label">
            <el-radio-button value="VIOLATION">违规样本</el-radio-button>
            <el-radio-button value="COMPLIANT">合规样本</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.label === 'VIOLATION'" label="违规类型">
          <el-select v-model="form.violationType" style="width: 100%">
            <el-option v-for="(label, code) in VIOLATION_TYPES" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="语料文本">
          <el-input v-model="form.transcript" type="textarea" :rows="4" placeholder="输入转写文本" />
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="form.reason" placeholder="标注理由（可选）" />
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
import { ElMessage } from 'element-plus'
import { Search, Plus, Download } from '@element-plus/icons-vue'
import api from '../api'
import { VIOLATION_TYPES, formatTime } from '../constants'

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 10
const loading = ref(false)
const labelFilter = ref('')
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ label: 'VIOLATION', violationType: 'INSULT', transcript: '', reason: '' })

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
    ElMessage.success('已新增')
    dialogVisible.value = false
    reload()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await api.deleteCorpus(row.id)
  ElMessage.success('已删除')
  reload()
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

onMounted(load)
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
  font-size: 13px;
  line-height: 1.7;
}
.mb-16 {
  margin-bottom: 16px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
</style>