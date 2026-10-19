<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">知识库 / SOP</div>
          <span class="chip info">共 {{ total }} 条</span>
          <span v-if="!canWrite" class="chip">只读</span>
        </div>
        <div class="actions">
          <el-input
            v-model="keyword"
            placeholder="搜索标题 / 正文"
            clearable
            class="search"
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select
            v-model="categoryFilter"
            placeholder="全部分类"
            clearable
            filterable
            allow-create
            class="category-select"
            @change="applyFilters"
          >
            <el-option v-for="c in KNOWLEDGE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select
            v-model="enabledFilter"
            placeholder="全部状态"
            clearable
            class="enabled-select"
            @change="applyFilters"
          >
            <el-option label="启用" value="true" />
            <el-option label="停用" value="false" />
          </el-select>
          <el-button type="primary" :icon="Search" :loading="loading" @click="applyFilters">查询</el-button>
          <el-button link @click="resetFilters">重置</el-button>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建条目</el-button>
        </div>
      </div>
      <div class="hint tiny dim">
        知识库用于沉淀话术规范、违规示例与处罚标准，供人工复核与 AI 复筛参考；
        所有登录用户可查看，管理员 / 复核员可维护。
      </div>
    </section>

    <!-- 列表 -->
    <section class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" @row-click="openDetail">
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="min-w-0">
              <div class="strong ellipsis" :title="row.title">{{ row.title || '未命名条目' }}</div>
              <div class="tiny dim mono">#{{ row.id ?? '—' }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="130">
          <template #default="{ row }">
            <span v-if="row.category" class="chip info">{{ row.category }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="违规类型" width="130">
          <template #default="{ row }">
            <span v-if="row.violationType" class="chip warn">{{ violationLabel(row.violationType) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="标签" min-width="190">
          <template #default="{ row }">
            <div v-if="row.tags.length" class="tag-cell">
              <span v-for="t in row.tags.slice(0, 3)" :key="t" class="chip">{{ t }}</span>
              <span v-if="row.tags.length > 3" class="tiny dim">+{{ row.tags.length - 3 }}</span>
            </div>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'">
              {{ row.enabled === false ? '停用' : row.enabled === true ? '启用' : '未知' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span v-if="row.updatedAt" class="num dim" :title="formatRelative(row.updatedAt)">
              {{ formatTime(row.updatedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button v-if="canWrite" size="small" link type="primary" @click.stop="openEdit(row)">
              编辑
            </el-button>
            <el-popconfirm
              v-if="canWrite"
              title="删除后不可恢复，确认删除该知识库条目？"
              width="250"
              @confirm="remove(row)"
            >
              <template #reference>
                <el-button size="small" link type="danger" @click.stop>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Reading /></el-icon>
            <div class="empty-title">{{ hasFilters ? '没有匹配的条目' : '知识库还是空的' }}</div>
            <div class="tiny dim">
              {{ hasFilters
                ? '试试清空搜索词、分类或状态筛选'
                : (canWrite ? '点击右上角「新建条目」沉淀话术规范与违规示例' : '暂无知识库内容') }}
            </div>
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

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" size="640px" :with-header="false" append-to-body>
      <div v-if="detail" class="detail">
        <div class="detail-head">
          <div class="min-w-0">
            <div class="detail-title">{{ detail.title || '未命名条目' }}</div>
            <div class="detail-chips">
              <span class="chip" :class="detail.enabled === false ? 'info' : 'ok'">
                {{ detail.enabled === false ? '已停用' : '启用' }}
              </span>
              <span v-if="detail.category" class="chip info">{{ detail.category }}</span>
              <span v-if="detail.violationType" class="chip warn">
                {{ violationLabel(detail.violationType) }}
              </span>
            </div>
          </div>
          <div class="detail-actions">
            <el-button v-if="canWrite" size="small" @click="openEdit(detail)">编辑</el-button>
            <el-button :icon="Close" circle size="small" @click="detailVisible = false" />
          </div>
        </div>

        <div v-if="detail.tags.length" class="detail-tags">
          <span class="tiny dim">标签</span>
          <span v-for="t in detail.tags" :key="t" class="chip">{{ t }}</span>
        </div>

        <div class="detail-meta tiny dim">
          创建：{{ detail.createdBy || '—' }} · {{ formatTime(detail.createdAt) }}
          <span class="sep">·</span>
          更新：{{ formatTime(detail.updatedAt) }}
        </div>

        <div class="block-title mt-14">正文</div>
        <pre class="kb-content">{{ detail.content || '（暂无正文）' }}</pre>
      </div>
    </el-drawer>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑知识库条目' : '新建知识库条目'"
      width="640px"
      top="6vh"
      append-to-body
      class="knowledge-dialog"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent="submit">
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            maxlength="200"
            show-word-limit
            placeholder="如：催收话术红线"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select
            v-model="form.category"
            filterable
            allow-create
            clearable
            placeholder="选择或输入分类"
            style="width: 100%"
          >
            <el-option v-for="c in KNOWLEDGE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="违规类型">
          <el-select v-model="form.violationType" clearable placeholder="可选，关联敏感词违规类型" style="width: 100%">
            <el-option
              v-for="(label, value) in VIOLATION_TYPES"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入标签后回车添加"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="10"
            maxlength="20000"
            show-word-limit
            placeholder="支持 Markdown / 纯文本，可记录话术正反例、判定标准与处置建议"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="tiny dim form-hint">停用后仍保留在列表中，供管理员后续维护</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editing ? '保存修改' : '创建条目' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Plus, Search } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import { formatRelative, formatTime, VIOLATION_TYPES } from '../constants'
import {
  KNOWLEDGE_CATEGORIES,
  knowledgeTagsPayload
} from '../utils/knowledge'

// 写操作仅 ADMIN / REVIEWER；VIEWER 只读（后端独立鉴权）
const canWrite = computed(() => auth.hasRole('ADMIN', 'REVIEWER'))

const pageSize = 10
const rows = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const error = ref('')

const keyword = ref('')
const categoryFilter = ref('')
const enabledFilter = ref('')

const hasFilters = computed(
  () => !!(keyword.value.trim() || categoryFilter.value || enabledFilter.value)
)

const violationLabel = (type) => VIOLATION_TYPES[type] || type || '—'

/** 403 / 404 给出可读降级文案（列表接口 silent，不弹全局错误） */
function knowledgeErrorMessage(err) {
  const status = err?.status
  if (status === 403) return '当前账号无权限查看知识库（请重新登录）'
  if (status === 404) return '后端尚未提供知识库接口（HTTP 404），请确认后端版本'
  return err?.message || '知识库加载失败，请稍后重试'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listKnowledge({
      keyword: keyword.value.trim() || undefined,
      category: categoryFilter.value || undefined,
      enabled: enabledFilter.value || undefined,
      page: page.value,
      size: pageSize
    })
    rows.value = data.items
    total.value = data.total
    // 删除 / 过滤后当前页可能越界：回退到最后一页重拉
    const maxPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (!data.items.length && page.value > 0 && data.total > 0) {
      page.value = maxPage - 1
      await load()
    }
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = knowledgeErrorMessage(e)
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function resetFilters() {
  keyword.value = ''
  categoryFilter.value = ''
  enabledFilter.value = ''
  page.value = 0
  load()
}

/* ---------- 详情 ---------- */

const detailVisible = ref(false)
const detail = ref(null)

function openDetail(row) {
  detail.value = row
  detailVisible.value = true
}

/* ---------- 新建 / 编辑 ---------- */

const dialogVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const formRef = ref(null)
const form = reactive({
  title: '',
  category: '',
  violationType: '',
  tags: [],
  content: '',
  enabled: true
})

const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 200, message: '标题最多 200 个字符', trigger: 'blur' }
  ]
}

function resetForm() {
  editing.value = null
  form.title = ''
  form.category = ''
  form.violationType = ''
  form.tags = []
  form.content = ''
  form.enabled = true
  formRef.value?.clearValidate?.()
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.title = row.title || ''
  form.category = row.category || ''
  form.violationType = row.violationType || ''
  form.tags = [...(row.tags || [])]
  form.content = row.content || ''
  form.enabled = row.enabled !== false
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      category: form.category.trim() || null,
      violationType: form.violationType || null,
      tags: knowledgeTagsPayload(form.tags) || null,
      content: form.content.trim() || null,
      enabled: !!form.enabled
    }
    let saved = null
    if (editing.value) {
      saved = await api.updateKnowledge(editing.value.id, payload)
      ElMessage.success('知识库条目已更新')
    } else {
      saved = await api.createKnowledge(payload)
      ElMessage.success('知识库条目已创建')
    }
    dialogVisible.value = false
    // 详情抽屉打开时同步刷新内容
    if (saved && detail.value && detail.value.id === saved.id) detail.value = saved
    await load()
  } catch {
    // 请求层已统一提示（403 / 校验失败等）
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await api.deleteKnowledge(row.id)
    ElMessage.success(`已删除「${row.title || row.id}」`)
    if (detail.value?.id === row.id) {
      detailVisible.value = false
      detail.value = null
    }
    await load()
  } catch {
    // 请求层已统一提示
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 18px 10px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  width: 220px;
}
.category-select {
  width: 140px;
}
.enabled-select {
  width: 120px;
}
.hint {
  padding: 0 18px 14px;
  line-height: 1.7;
}
.min-w-0 {
  min-width: 0;
}
.ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}
.empty-icon {
  color: var(--ink-300);
}
:deep(.el-table__row) {
  cursor: pointer;
}

/* 详情抽屉 */
.detail {
  padding: 20px 22px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.detail-title {
  font-size: 17px;
  font-weight: 650;
  color: var(--ink-900);
  line-height: 1.45;
  word-break: break-word;
}
.detail-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.detail-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.detail-meta {
  margin-top: 10px;
  line-height: 1.7;
}
.mt-14 {
  margin-top: 16px;
}
.kb-content {
  margin: 0;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  font-size: 13px;
  line-height: 1.85;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

.form-hint {
  margin-left: 10px;
}
:global(.knowledge-dialog .el-dialog__body) {
  max-height: 68vh;
  overflow-y: auto;
  padding-top: 8px;
}

.error-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 14px 18px 0;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
}
</style>
