<template>
  <div class="page stack">
    <!-- 加载失败：KPI 会残留上次数据 / 占位符，必须明确提示并可重试（否则与「无数据」不可区分） -->
    <div v-if="error" class="error-banner" role="alert">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <el-button link type="primary" size="small" :loading="loading" @click="load">
        {{ t('common.retry') }}
      </el-button>
    </div>

    <!-- KPI -->
    <div class="kpi-grid">
      <StatCard
        :label="t('dashboard.kpi.total')"
        :value="overview?.totalUploads ?? '-'"
        icon="Files"
        tone="brand"
        :sub="t('dashboard.kpi.totalSub', { count: overview?.todayUploads ?? 0 })"
        clickable
        @click="router.push('/recordings')"
      />
      <StatCard
        :label="t('dashboard.kpi.processing')"
        :value="processingCount"
        icon="Loading"
        tone="info"
        :sub="t('dashboard.kpi.processingSub')"
      />
      <StatCard
        :label="STATUS_META.NEEDS_REVIEW.label"
        :value="count('NEEDS_REVIEW')"
        icon="DocumentChecked"
        tone="warn"
        :sub="t('dashboard.kpi.needsReviewSub')"
        clickable
        @click="router.push('/review')"
      />
      <StatCard
        :label="STATUS_META.VIOLATION_CONFIRMED.label"
        :value="count('VIOLATION_CONFIRMED')"
        icon="WarningFilled"
        tone="danger"
        :sub="t('dashboard.kpi.confirmedSub')"
        clickable
        @click="router.push('/recordings?statuses=VIOLATION_CONFIRMED')"
      />
      <StatCard
        :label="STATUS_META.COMPLIANT.label"
        :value="count('COMPLIANT')"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="t('dashboard.kpi.compliantSub', { count: count('FALSE_POSITIVE') })"
      />
      <StatCard
        :label="STATUS_META.FAILED.label"
        :value="count('FAILED')"
        icon="CircleCloseFilled"
        tone="danger"
        :sub="t('dashboard.kpi.failedSub')"
        clickable
        @click="router.push('/recordings?statuses=FAILED')"
      />
    </div>

    <!-- 趋势 + 处置分布 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('dashboard.trend.title') }}</div>
          <span class="tiny dim">{{ t('dashboard.trend.sub') }}</span>
        </div>
        <div class="panel-body">
          <TrendChart :data="overview?.last7Days || []" :height="196" />
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('dashboard.distribution.title') }}</div>
          <span class="tiny dim">{{
            t('dashboard.distribution.total', { count: overview?.totalUploads ?? 0 })
          }}</span>
        </div>
        <div class="panel-body dist">
          <div v-for="row in distribution" :key="row.key" class="dist-row">
            <div class="dist-top">
              <span class="dist-label">
                <span class="dot" :style="{ background: row.color }"></span>
                {{ row.label }}
              </span>
              <span class="dist-value num"
                >{{ row.value }}<span class="dist-pct">{{ row.pct }}%</span></span
              >
            </div>
            <div class="dist-track">
              <div class="dist-fill" :style="{ width: row.pct + '%', background: row.color }"></div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- 最近录音 + 知识库 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">{{ t('dashboard.recent.title') }}</div>
          <el-button link type="primary" size="small" @click="router.push('/recordings')">
            {{ t('dashboard.recent.viewAll')
            }}<el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </div>
        <el-table
          :data="recent"
          size="small"
          @row-click="(row) => router.push(`/recordings?id=${row.id}`)"
        >
          <el-table-column :label="t('dashboard.recent.file')" min-width="200">
            <template #default="{ row }">
              <div class="cell-file">
                <el-icon class="cell-file-icon"><Microphone /></el-icon>
                <span class="ellipsis">{{ row.fileName }}</span>
                <span class="cell-file-dur num">{{ formatDuration(row.durationSeconds) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('dashboard.recent.hits')" width="64" align="center">
            <template #default="{ row }">
              <span v-if="row.hitCount" class="chip danger">{{ row.hitCount }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.status')" width="142" align="center">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
          <el-table-column :label="t('dashboard.recent.uploadTime')" width="176">
            <template #default="{ row }">
              <span class="cell-time num dim">{{ formatTime(row.uploadTime) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-block">
              <el-icon :size="30" class="empty-icon"><Microphone /></el-icon>
              <div class="empty-title">{{ t('dashboard.recent.emptyTitle') }}</div>
              <div class="tiny dim">{{ t('dashboard.recent.emptyHint') }}</div>
            </div>
          </template>
        </el-table>
      </section>

      <section class="panel knowledge">
        <div class="panel-head">
          <div class="section-title">{{ t('dashboard.knowledge.title') }}</div>
        </div>
        <div class="panel-body">
          <div class="kb-grid">
            <router-link class="kb-item" to="/dictionary">
              <div class="kb-label">{{ t('dashboard.knowledge.dictionaryTotal') }}</div>
              <div class="kb-value num">{{ overview?.dictionary?.total ?? '-' }}</div>
              <div class="kb-sub">
                {{
                  t('dashboard.knowledge.dictionaryEnabled', {
                    count: overview?.dictionary?.enabled ?? '-'
                  })
                }}
              </div>
            </router-link>
            <router-link class="kb-item" to="/dictionary?source=MINED&enabled=false">
              <div class="kb-label">{{ t('dashboard.knowledge.minedPending') }}</div>
              <div
                class="kb-value num"
                :class="{ alert: (overview?.dictionary?.minedPending ?? 0) > 0 }"
              >
                {{ overview?.dictionary?.minedPending ?? 0 }}
              </div>
              <div class="kb-sub">{{ t('dashboard.knowledge.minedSub') }}</div>
            </router-link>
            <router-link class="kb-item" to="/corpus">
              <div class="kb-label">{{ t('dashboard.knowledge.violationCorpus') }}</div>
              <div class="kb-value num">{{ overview?.corpus?.violation ?? 0 }}</div>
              <div class="kb-sub">{{ t('dashboard.knowledge.violationSub') }}</div>
            </router-link>
            <router-link class="kb-item" to="/corpus">
              <div class="kb-label">{{ t('dashboard.knowledge.compliantCorpus') }}</div>
              <div class="kb-value num">{{ overview?.corpus?.compliant ?? 0 }}</div>
              <div class="kb-sub">{{ t('dashboard.knowledge.compliantSub') }}</div>
            </router-link>
          </div>

          <div class="loop-tip">
            <el-icon class="loop-icon"><Refresh /></el-icon>
            <div>
              {{ t('dashboard.knowledge.loopTip') }}
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WarningFilled } from '@element-plus/icons-vue'
import api from '../api'
import StatCard from '../components/StatCard.vue'
import StatusTag from '../components/StatusTag.vue'
import TrendChart from '../components/TrendChart.vue'
import { formatDuration, formatTime, PROCESSING_STATUSES, STATUS_META } from '../constants'
import { useI18n } from '../i18n'
import overviewState from '../state/overview'
import { ArrowRight, Microphone, Refresh } from '../components/icons.js'

const { t } = useI18n()
const router = useRouter()
/** 概览走全局单例（与 App 轮询 / 语料库 / 敏感词库共享请求与短缓存） */
const overview = computed(() => overviewState.data)
const recent = ref([])
const loading = ref(false)
const error = ref('')

const count = (status) => overview.value?.statusCounts?.[status] ?? 0

const processingCount = computed(() =>
  PROCESSING_STATUSES.reduce((sum, s) => sum + (overview.value?.statusCounts?.[s] ?? 0), 0)
)

const distribution = computed(() => {
  const total = Math.max(1, overview.value?.totalUploads ?? 0)
  const rows = [
    {
      key: 'processing',
      label: t('dashboard.distribution.processing'),
      value: processingCount.value,
      color: '#3b6ef6'
    },
    {
      key: 'NEEDS_REVIEW',
      label: STATUS_META.NEEDS_REVIEW.label,
      value: count('NEEDS_REVIEW'),
      color: '#f59e0b'
    },
    {
      key: 'COMPLIANT',
      label: STATUS_META.COMPLIANT.label,
      value: count('COMPLIANT'),
      color: '#16a34a'
    },
    {
      key: 'VIOLATION_CONFIRMED',
      label: STATUS_META.VIOLATION_CONFIRMED.label,
      value: count('VIOLATION_CONFIRMED'),
      color: '#ef4444'
    },
    {
      key: 'FALSE_POSITIVE',
      label: STATUS_META.FALSE_POSITIVE.label,
      value: count('FALSE_POSITIVE'),
      color: '#64748b'
    },
    { key: 'FAILED', label: STATUS_META.FAILED.label, value: count('FAILED'), color: '#b91c1c' }
  ]
  return rows.map((r) => ({ ...r, pct: Math.round((r.value / total) * 100) }))
})

/** 加载失败文案：403 / 404 给可读降级，其余沿用请求层 message */
function loadErrorMessage(reason) {
  const status = reason?.status
  if (status === 403) return t('dashboard.error.forbidden')
  if (status === 404) return t('dashboard.error.notFound')
  return reason?.message || t('dashboard.error.loadFailed')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 概览与最近录音互不依赖：任一失败都提示，但成功的那份继续展示
    const [, list] = await Promise.all([
      overviewState.load(),
      api.listRecordings({ page: 0, size: 7 })
    ])
    recent.value = list.content || []
  } catch (reason) {
    error.value = loadErrorMessage(reason)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 加载失败横幅：与知识库 / 审计等页面保持同一视觉语言 */
.error-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
}
@media (max-width: 1500px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1080px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 16px;
}
/* 1280 笔记本宽度下趋势 + 处置分布仍并排，低于 1200 再堆叠 */
@media (max-width: 1200px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 分布条 */
.dist {
  display: flex;
  flex-direction: column;
  gap: 13px;
  padding-top: 4px;
}
.dist-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}
.dist-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
}
.dist-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}
.dist-pct {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--el-text-color-placeholder);
}
.dist-track {
  height: 7px;
  border-radius: 999px;
  background: var(--ink-100);
  overflow: hidden;
}
.dist-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 表格内的文件列 */
.cell-file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.cell-file-icon {
  color: var(--brand-500);
  flex: none;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 时长并入文件列，避免窄容器内多一列被裁切 */
.cell-file-dur {
  flex: none;
  margin-left: 2px;
  padding-left: 8px;
  border-left: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
/* 上传时间保持单行，避免在 172px 列内换行 */
.cell-time {
  white-space: nowrap;
}

:deep(.el-table__row) {
  cursor: pointer;
}

/* 表格空态：圆形图标底座，避免空白区域过于生硬 */
.empty-block .empty-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-300);
  background: var(--ink-50);
  border: 1px dashed var(--border-strong);
  margin-bottom: 8px;
}
.empty-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}

/* 知识库 */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.kb-item {
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 13px 15px;
  background: #fbfcfe;
  transition: all 0.18s ease;
}
.kb-item:hover {
  border-color: var(--brand-300);
  background: var(--brand-50);
  transform: translateY(-1px);
}
.kb-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kb-value {
  font-size: 21px;
  font-weight: 700;
  color: var(--ink-800);
  margin-top: 2px;
  line-height: 1.2;
}
.kb-value.alert {
  color: #b06a06;
}
.kb-sub {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.loop-tip {
  display: flex;
  gap: 9px;
  margin-top: 14px;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: 12px;
  line-height: 1.7;
}
.loop-icon {
  margin-top: 2px;
  flex: none;
}
</style>
