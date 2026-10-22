<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="toolbar-head">
          <div class="row-wrap">
            <div class="section-title">知识库 / SOP</div>
            <span class="chip info">共 {{ total }} 条</span>
            <span v-if="!canWrite" class="chip">只读</span>
          </div>
          <div class="toolbar-actions">
            <el-button v-if="canWrite" :icon="Aim" @click="openSearchPreview">检索预览</el-button>
            <el-button v-if="canWrite" :icon="Upload" @click="openImportDialog">导入文档</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建条目</el-button>
          </div>
        </div>
        <div class="toolbar-filters">
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
        </div>
      </div>
      <div class="hint tiny dim">
        知识库用于沉淀话术规范、违规示例与处罚标准，供人工复核与 AI 复筛参考；
        支持导入 PDF / Word / TXT / Markdown 文档并自动解析建立索引；
        所有登录用户可查看，管理员 / 复核员可维护。
      </div>
    </section>

    <!-- 列表 -->
    <section ref="tableWrap" class="panel">
      <div v-if="error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <el-button link type="primary" size="small" @click="load">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" @row-click="openDetail">
        <el-table-column label="标题" min-width="168">
          <template #default="{ row }">
            <div class="min-w-0">
              <div class="strong ellipsis" :title="row.title">{{ row.title || '未命名条目' }}</div>
              <div class="tiny dim mono">#{{ row.id ?? '—' }}</div>
              <div
                v-if="row.sourceFilename"
                class="tiny dim source-file"
                :title="row.sourceFilename"
              >
                <el-icon :size="12"><Document /></el-icon>
                <span class="source-name">{{ row.sourceFilename }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="104">
          <template #default="{ row }">
            <span v-if="row.category" class="chip info">{{ row.category }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="违规类型" width="104">
          <template #default="{ row }">
            <span v-if="row.violationType" class="chip warn">{{ violationLabel(row.violationType) }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column v-if="!compactColumns" label="标签" min-width="92">
          <template #default="{ row }">
            <div v-if="row.tags.length" class="tag-cell" :title="row.tags.join('、')">
              <span v-for="t in row.tags.slice(0, 2)" :key="t" class="chip">{{ t }}</span>
              <span v-if="row.tags.length > 2" class="tiny dim">+{{ row.tags.length - 2 }}</span>
            </div>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="78" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <span class="chip" :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'">
              {{ row.enabled === false ? '停用' : row.enabled === true ? '启用' : '未知' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="索引状态" width="88" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <el-tooltip
              v-if="isFailedIndex(row)"
              :content="row.indexError || '索引失败，可点击「重建索引」重试'"
              placement="top"
              :show-after="200"
              :popper-style="{ maxWidth: '420px', wordBreak: 'break-all' }"
            >
              <span class="chip danger">{{ indexStatusMeta(row.indexStatus).label }}</span>
            </el-tooltip>
            <span
              v-else-if="row.indexStatus"
              class="chip"
              :class="indexStatusMeta(row.indexStatus).type"
            >
              {{ indexStatusMeta(row.indexStatus).label }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="120">
          <template #default="{ row }">
            <span v-if="row.updatedAt" class="num dim" :title="formatTime(row.updatedAt)">
              {{ formatTimeShort(row.updatedAt) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="196" align="right" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button size="small" link type="primary" @click.stop="openDetail(row)">详情</el-button>
              <el-button v-if="canWrite" size="small" link type="primary" @click.stop="openEdit(row)">
                编辑
              </el-button>
              <el-button
                v-if="canWrite"
                size="small"
                link
                type="primary"
                :loading="reindexingId === row.id"
                @click.stop="reindexRow(row)"
              >
                重建索引
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
            </div>
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
            <el-button
              v-if="canWrite"
              size="small"
              :loading="reindexingId === detail.id"
              @click="reindexRow(detail)"
            >
              重建索引
            </el-button>
            <el-button :icon="Close" circle size="small" @click="detailVisible = false" aria-label="关闭" title="关闭" />
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

        <div class="block-title mt-18">索引信息</div>
        <div class="index-grid">
          <div class="index-item">
            <span class="tiny dim">索引状态</span>
            <span class="chip" :class="indexStatusMeta(detail.indexStatus).type">
              {{ indexStatusMeta(detail.indexStatus).label }}
            </span>
          </div>
          <div class="index-item">
            <span class="tiny dim">来源</span>
            <span class="ellipsis" :title="detail.sourceType || ''">
              {{ detail.sourceType === 'DOCUMENT'
                ? '文档导入'
                : detail.sourceType === 'MANUAL'
                  ? '手工录入'
                  : (detail.sourceType || '—') }}
            </span>
          </div>
          <div class="index-item">
            <span class="tiny dim">来源文件</span>
            <span class="ellipsis" :title="detail.sourceFilename || ''">
              {{ detail.sourceFilename || '—' }}
            </span>
          </div>
          <div class="index-item">
            <span class="tiny dim">文件大小</span>
            <span class="num">{{ formatFileSize(detail.sourceSize) }}</span>
          </div>
          <div class="index-item">
            <span class="tiny dim">分块数</span>
            <span class="num">{{ detail.chunkCount ?? '—' }}</span>
          </div>
          <div class="index-item">
            <span class="tiny dim">索引时间</span>
            <span>{{ detail.indexedAt ? formatTime(detail.indexedAt) : '—' }}</span>
          </div>
        </div>
        <div v-if="detail.indexError" class="index-error">
          <el-icon :size="14"><WarningFilled /></el-icon>
          <span>{{ detail.indexError }}</span>
        </div>

        <div class="block-title mt-18">正文</div>
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

    <!-- 导入文档 -->
    <el-dialog
      v-model="importVisible"
      title="导入知识库文档"
      width="560px"
      append-to-body
      :close-on-click-modal="!importing"
      :close-on-press-escape="!importing"
      :show-close="!importing"
      @closed="resetImport"
    >
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".pdf,.docx,.txt,.md"
        :disabled="importing"
        :on-change="onFileChange"
        :on-exceed="onFileExceed"
        :on-remove="onFileRemove"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文档拖到此处，或 <em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 PDF / DOCX / TXT / Markdown，单文件不超过 20 MB；上传后后台解析并建立索引。
          </div>
        </template>
      </el-upload>

      <el-form label-width="72px" class="import-form" @submit.prevent="submitImport">
        <el-form-item label="标题">
          <el-input
            v-model="importForm.title"
            maxlength="200"
            placeholder="可选，留空使用文件名"
            :disabled="importing"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select
            v-model="importForm.category"
            filterable
            allow-create
            clearable
            placeholder="可选，选择或输入分类"
            style="width: 100%"
            :disabled="importing"
          >
            <el-option v-for="c in KNOWLEDGE_CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="importing" class="tiny dim import-progress">
        <el-icon class="is-loading"><Loading /></el-icon>
        正在上传文档，请勿关闭对话框…
      </div>

      <template #footer>
        <el-button :disabled="importing" @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" :disabled="importing" @click="submitImport">
          {{ importing ? '上传中…' : '开始导入' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 检索预览 -->
    <el-dialog
      v-model="searchVisible"
      title="知识库检索预览"
      width="720px"
      top="8vh"
      append-to-body
    >
      <div class="search-bar">
        <el-input
          v-model="searchForm.query"
          placeholder="输入问题或关键词，查看语义检索命中的知识片段"
          clearable
          :disabled="searching"
          @keyup.enter="runSearchPreview"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-input-number
          v-model="searchForm.topK"
          :min="1"
          :max="10"
          :precision="0"
          :disabled="searching"
          controls-position="right"
          class="topk-input"
        />
        <el-button type="primary" :loading="searching" @click="runSearchPreview">检索</el-button>
      </div>
      <div class="tiny dim search-hint">Top K 为召回条数（1~10），检索使用知识库已就绪的向量片段。</div>

      <div v-if="searching" class="search-state tiny dim">
        <el-icon class="is-loading"><Loading /></el-icon>
        正在检索…
      </div>
      <div v-else-if="searchError" class="error-banner search-error">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ searchError }}</span>
      </div>
      <div v-else-if="searchDone && !searchResults.length" class="empty-block">
        <el-icon :size="34" class="empty-icon"><Search /></el-icon>
        <div class="empty-title">没有召回相关知识片段</div>
        <div class="tiny dim">试试换一个说法，或确认 RAG 已启用且知识库索引已就绪</div>
      </div>
      <div v-else-if="searchResults.length" class="search-results">
        <div
          v-for="(item, idx) in searchResults"
          :key="`${idx}-${item.chunkId ?? item.entryId ?? ''}`"
          class="search-hit"
        >
          <div class="search-hit-head">
            <span class="chip brand num">相似度 {{ formatScore(item.score) }}</span>
            <span class="strong ellipsis" :title="item.title">{{ item.title || '未命名条目' }}</span>
            <span v-if="item.category" class="chip info">{{ item.category }}</span>
            <span class="tiny dim mono">#{{ item.entryId ?? '—' }}</span>
          </div>
          <pre class="search-hit-content">{{ item.content || '（无内容）' }}</pre>
        </div>
      </div>
      <div v-else class="tiny dim search-state">输入查询后点击「检索」查看召回结果。</div>

      <template #footer>
        <el-button @click="searchVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Aim, Close, Plus, Search, Upload } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import { formatTime, formatTimeShort, VIOLATION_TYPES } from '../constants'
import {
  KNOWLEDGE_CATEGORIES,
  knowledgeTagsPayload
} from '../utils/knowledge'
import { formatFileSize, indexStatusMeta, isIndexInProgress } from '../utils/rag'
import { useElementWidth } from '../utils/elementWidth'

/* 列表列宽自适应：内容区放不下「标签」列时先收起它（标签在详情抽屉仍可见），
   让 1024/1280 下表格不被固定操作列压住「更新时间」。 */
const tableWrap = ref(null)
const tableWidth = useElementWidth(tableWrap)
const compactColumns = computed(() => tableWidth.value > 0 && tableWidth.value < 880)

// 写操作仅 ADMIN / REVIEWER；VIEWER 只读（后端独立鉴权）
const canWrite = computed(() => auth.hasRole('ADMIN', 'REVIEWER'))

/** 索引处理中条目的自动刷新间隔（秒） */
const REFRESH_SECONDS = 3

/** FAILED 状态在列表里用 tooltip 展示 indexError */
function isFailedIndex(row) {
  return String(row?.indexStatus || '').toUpperCase() === 'FAILED'
}

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

async function load({ background = false } = {}) {
  if (background && loading.value) return
  if (!background) {
    loading.value = true
    error.value = ''
  }
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
    error.value = ''
    // 删除 / 过滤后当前页可能越界：回退到最后一页重拉
    const maxPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (!data.items.length && page.value > 0 && data.total > 0) {
      page.value = maxPage - 1
      await load({ background })
    }
  } catch (e) {
    // 自动刷新失败时保留当前列表，避免静默轮询把界面清空
    if (background) return
    rows.value = []
    total.value = 0
    error.value = knowledgeErrorMessage(e)
  } finally {
    if (!background) loading.value = false
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

/* ---------- 重建索引 ---------- */

const reindexingId = ref(null)

async function reindexRow(row) {
  if (!row?.id || reindexingId.value != null) return
  reindexingId.value = row.id
  try {
    await api.reindexKnowledge(row.id)
    ElMessage.success(`「${row.title || row.id}」已提交重建索引，后台处理中`)
    // 详情抽屉打开时同步为处理中，避免继续显示旧状态
    if (detail.value?.id === row.id) {
      detail.value = { ...detail.value, indexStatus: 'PENDING', indexError: '' }
    }
    await load({ background: true })
    const fresh = rows.value.find((item) => item.id === row.id)
    if (fresh && detail.value?.id === row.id) detail.value = fresh
  } catch {
    // 请求层已统一提示（403 / 404 等）
  } finally {
    reindexingId.value = null
  }
}

/* ---------- 导入文档 ---------- */

/** 与后端契约一致：pdf/docx/txt/md，单文件上限 20 MB */
const DOCUMENT_EXTENSIONS = ['pdf', 'docx', 'txt', 'md']
const DOCUMENT_MAX_BYTES = 20 * 1024 * 1024

const importVisible = ref(false)
const importing = ref(false)
const uploadRef = ref(null)
const selectedFile = ref(null)
const importForm = reactive({ title: '', category: '' })

function openImportDialog() {
  resetImport()
  importVisible.value = true
}

function resetImport() {
  selectedFile.value = null
  importForm.title = ''
  importForm.category = ''
  uploadRef.value?.clearFiles?.()
}

function documentExtension(name) {
  const text = String(name || '').trim().toLowerCase()
  const dot = text.lastIndexOf('.')
  return dot >= 0 ? text.slice(dot + 1) : ''
}

/** 客户端预校验扩展名与大小，减少无效上传；最终仍以后端校验为准 */
function acceptDocument(file) {
  const raw = file?.raw || file
  const name = raw?.name || ''
  if (!DOCUMENT_EXTENSIONS.includes(documentExtension(name))) {
    ElMessage.warning('仅支持 PDF / DOCX / TXT / MD 文档')
    return null
  }
  if (typeof raw?.size === 'number' && raw.size > DOCUMENT_MAX_BYTES) {
    ElMessage.warning('文档超过 20 MB，请压缩或拆分后再导入')
    return null
  }
  return raw
}

function onFileChange(file, fileList) {
  // limit=1 时替换选择应以最后一份为准
  const chosen = Array.isArray(fileList) && fileList.length
    ? fileList[fileList.length - 1]
    : file
  const raw = acceptDocument(chosen)
  if (!raw) {
    selectedFile.value = null
    uploadRef.value?.clearFiles?.()
    return
  }
  selectedFile.value = raw
}

function onFileExceed() {
  ElMessage.warning('一次只能导入一份文档，请先移除已选择的文件')
}

function onFileRemove() {
  selectedFile.value = null
}

async function submitImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要导入的文档')
    return
  }
  const file = selectedFile.value
  importing.value = true
  try {
    const entry = await api.uploadKnowledgeDocument(file, {
      title: importForm.title.trim(),
      category: importForm.category.trim()
    })
    ElMessage.success(
      `「${entry.title || file.name || '文档'}」已导入，后台正在解析并建立索引`
    )
    importVisible.value = false
    resetImport()
    await load()
  } catch {
    // 请求层已统一提示（重复文档 409 / 格式不支持等由后端校验）
  } finally {
    importing.value = false
  }
}

/* ---------- 检索预览 ---------- */

const searchVisible = ref(false)
const searching = ref(false)
const searchDone = ref(false)
const searchError = ref('')
const searchResults = ref([])
const searchForm = reactive({ query: '', topK: 4 })

function openSearchPreview() {
  searchVisible.value = true
}

/** 403 / 404 给出可读降级文案（检索接口 silent） */
function searchErrorMessage(err) {
  const status = err?.status
  if (status === 403) return '当前账号无检索权限（需要 ADMIN / REVIEWER）'
  if (status === 404) return '后端尚未提供语义检索接口（HTTP 404），请确认后端版本'
  return err?.message || '检索失败，请稍后重试'
}

async function runSearchPreview() {
  if (searching.value) return
  const query = searchForm.query.trim()
  if (!query) {
    ElMessage.warning('请输入检索关键词')
    return
  }
  const topK = Math.min(10, Math.max(1, Math.round(Number(searchForm.topK) || 4)))
  searchForm.topK = topK
  searching.value = true
  searchError.value = ''
  searchDone.value = false
  try {
    const data = await api.searchKnowledge({ query, topK })
    searchResults.value = data.results
  } catch (e) {
    searchResults.value = []
    searchError.value = searchErrorMessage(e)
  } finally {
    searchDone.value = true
    searching.value = false
  }
}

function formatScore(score) {
  if (score === null || score === undefined) return '—'
  const num = Number(score)
  return Number.isFinite(num) ? num.toFixed(3) : '—'
}

// 索引处理中的条目每 3 秒静默刷新，等待后台解析 / 索引完成
let refreshTimer = null

onMounted(async () => {
  await load()
  refreshTimer = setInterval(() => {
    if (loading.value) return
    if (rows.value.some((row) => isIndexInProgress(row.indexStatus))) load({ background: true })
  }, REFRESH_SECONDS * 1000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
})
</script>

<style scoped>
/* 工具栏：首行「标题 + 主操作」，次行「筛选条件」，窄屏自然折行 */
.toolbar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 18px 12px;
}
.toolbar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.toolbar-filters {
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
/* 操作列：链接按钮用 gap 控制间距，避免 column 宽度吃紧后折行 */
.op-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  white-space: nowrap;
}
/* 窄列（状态 / 索引状态）用 12px 内边距，避免胶囊在 18px 内边距下被 ellipsis 截出「…」 */
:deep(.col-tight .cell) {
  padding-left: 12px;
  padding-right: 12px;
}
.op-cell :deep(.el-button + .el-button) {
  margin-left: 0;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}
.empty-icon {
  color: var(--ink-300);
}
:deep(.el-table__row) {
  cursor: pointer;
}

/* 详情抽屉：内边距交给全局 .el-drawer__body，避免双重留白 */
.detail {
  padding: 0;
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
/* 抽屉内的分组标题（与 Recordings / Audit 保持一致） */
.block-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 10px;
}
.mt-18 {
  margin-top: 18px;
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
  padding: 20px;
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

/* 文档来源 / 索引信息 */
.source-file {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  margin-top: 2px;
}
.source-file .el-icon {
  flex: none;
}
.source-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.index-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}
.index-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  font-size: 12.5px;
}
.index-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: #b3261e;
  font-size: 12.5px;
  line-height: 1.65;
  word-break: break-word;
}

/* 导入文档 / 检索预览 */
.import-form {
  margin-top: 16px;
}
.import-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.search-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.search-bar .el-input {
  flex: 1;
  min-width: 0;
}
.topk-input {
  width: 118px;
  flex: none;
}
.search-hint {
  margin-top: 8px;
  line-height: 1.6;
}
.search-state {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.search-error {
  margin: 16px 0 0;
}
.search-results {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 52vh;
  margin-top: 14px;
  overflow-y: auto;
}
.search-hit {
  padding: 11px 13px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
}
.search-hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.search-hit-content {
  max-height: 120px;
  margin: 8px 0 0;
  overflow: auto;
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
}
@media (max-width: 760px) {
  .index-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .search-bar {
    flex-wrap: wrap;
  }
  .search-bar .el-input {
    flex-basis: 100%;
  }
}
</style>
