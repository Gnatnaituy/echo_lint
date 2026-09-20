<template>
  <div class="page stack">
    <!-- KPI -->
    <div class="kpi-grid">
      <StatCard
        label="录音总数"
        :value="overview?.totalUploads ?? '-'"
        icon="Files"
        tone="brand"
        :sub="`今日新增 ${overview?.todayUploads ?? 0} 条`"
        clickable
        @click="router.push('/recordings')"
      />
      <StatCard
        label="处理中"
        :value="processingCount"
        icon="Loading"
        tone="info"
        sub="转写 / 初筛 / 复筛"
      />
      <StatCard
        label="待人工复检"
        :value="count('NEEDS_REVIEW')"
        icon="DocumentChecked"
        tone="warn"
        sub="初筛与复筛均判违规"
        clickable
        @click="router.push('/review')"
      />
      <StatCard
        label="确认违规"
        :value="count('VIOLATION_CONFIRMED')"
        icon="WarningFilled"
        tone="danger"
        sub="人工复检确认"
        clickable
        @click="router.push('/recordings?statuses=VIOLATION_CONFIRMED')"
      />
      <StatCard
        label="自动通过"
        :value="count('COMPLIANT')"
        icon="CircleCheckFilled"
        tone="ok"
        :sub="`复筛误报放行 ${count('FALSE_POSITIVE')} 条`"
      />
      <StatCard
        label="处理失败"
        :value="count('FAILED')"
        icon="CircleCloseFilled"
        tone="danger"
        sub="可重试"
        clickable
        @click="router.push('/recordings?statuses=FAILED')"
      />
    </div>

    <!-- 趋势 + 处置分布 -->
    <div class="two-col">
      <section class="panel">
        <div class="panel-head">
          <div class="section-title">近 7 日上传趋势</div>
          <span class="tiny dim">按上传日期统计</span>
        </div>
        <div class="panel-body">
          <TrendChart :data="overview?.last7Days || []" :height="196" />
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div class="section-title">处置分布</div>
          <span class="tiny dim">共 {{ overview?.totalUploads ?? 0 }} 条</span>
        </div>
        <div class="panel-body dist">
          <div v-for="row in distribution" :key="row.key" class="dist-row">
            <div class="dist-top">
              <span class="dist-label">
                <span class="dot" :style="{ background: row.color }"></span>
                {{ row.label }}
              </span>
              <span class="dist-value num">{{ row.value }}<span class="dist-pct">{{ row.pct }}%</span></span>
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
          <div class="section-title">最近录音</div>
          <el-button link type="primary" size="small" @click="router.push('/recordings')">
            查看全部<el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </div>
        <el-table :data="recent" size="small" @row-click="(row) => router.push(`/recordings?id=${row.id}`)">
          <el-table-column label="文件" min-width="230">
            <template #default="{ row }">
              <div class="cell-file">
                <el-icon class="cell-file-icon"><Microphone /></el-icon>
                <span class="ellipsis">{{ row.fileName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="88" align="right">
            <template #default="{ row }">
              <span class="num dim">{{ formatDuration(row.durationSeconds) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="命中" width="72" align="center">
            <template #default="{ row }">
              <span v-if="row.hitCount" class="chip danger">{{ row.hitCount }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="124" align="center">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="上传时间" width="150">
            <template #default="{ row }">
              <span class="num dim">{{ formatTime(row.uploadTime) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel knowledge">
        <div class="panel-head">
          <div class="section-title">知识库与闭环</div>
        </div>
        <div class="panel-body">
          <div class="kb-grid">
            <router-link class="kb-item" to="/dictionary">
              <div class="kb-label">敏感词总数</div>
              <div class="kb-value num">{{ overview?.dictionary?.total ?? '-' }}</div>
              <div class="kb-sub">启用 {{ overview?.dictionary?.enabled ?? '-' }}</div>
            </router-link>
            <router-link class="kb-item" to="/dictionary?source=MINED&enabled=false">
              <div class="kb-label">AI 挖掘待审核</div>
              <div class="kb-value num" :class="{ alert: (overview?.dictionary?.minedPending ?? 0) > 0 }">
                {{ overview?.dictionary?.minedPending ?? 0 }}
              </div>
              <div class="kb-sub">来自确认违规录音</div>
            </router-link>
            <router-link class="kb-item" to="/corpus">
              <div class="kb-label">违规语料</div>
              <div class="kb-value num">{{ overview?.corpus?.violation ?? 0 }}</div>
              <div class="kb-sub">few-shot 正例</div>
            </router-link>
            <router-link class="kb-item" to="/corpus">
              <div class="kb-label">合规语料</div>
              <div class="kb-value num">{{ overview?.corpus?.compliant ?? 0 }}</div>
              <div class="kb-sub">few-shot 反例</div>
            </router-link>
          </div>

          <div class="loop-tip">
            <el-icon class="loop-icon"><Refresh /></el-icon>
            <div>
              人工复检结论自动写入语料库，作为 AI 复筛的 few-shot 参考；
              确认违规的录音会由 AI 提炼新敏感词入库（默认停用，审核后启用）。
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
import api from '../api'
import StatCard from '../components/StatCard.vue'
import StatusTag from '../components/StatusTag.vue'
import TrendChart from '../components/TrendChart.vue'
import { formatDuration, formatTime, PROCESSING_STATUSES } from '../constants'

const router = useRouter()
const overview = ref(null)
const recent = ref([])

const count = (status) => overview.value?.statusCounts?.[status] ?? 0

const processingCount = computed(() =>
  PROCESSING_STATUSES.reduce((sum, s) => sum + (overview.value?.statusCounts?.[s] ?? 0), 0)
)

const distribution = computed(() => {
  const total = Math.max(1, overview.value?.totalUploads ?? 0)
  const rows = [
    { key: 'processing', label: '处理中', value: processingCount.value, color: '#3b6ef6' },
    { key: 'NEEDS_REVIEW', label: '待人工复检', value: count('NEEDS_REVIEW'), color: '#f59e0b' },
    { key: 'COMPLIANT', label: '自动通过', value: count('COMPLIANT'), color: '#16a34a' },
    { key: 'VIOLATION_CONFIRMED', label: '确认违规', value: count('VIOLATION_CONFIRMED'), color: '#ef4444' },
    { key: 'FALSE_POSITIVE', label: '误判放行', value: count('FALSE_POSITIVE'), color: '#64748b' },
    { key: 'FAILED', label: '处理失败', value: count('FAILED'), color: '#b91c1c' }
  ]
  return rows.map((r) => ({ ...r, pct: Math.round((r.value / total) * 100) }))
})

async function load() {
  try {
    const [ov, list] = await Promise.all([api.overview(), api.listRecordings({ page: 0, size: 7 })])
    overview.value = ov
    recent.value = list.content || []
  } catch {
    // 后端未就绪时保持空态
  }
}

onMounted(load)
</script>

<style scoped>
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

.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 16px;
}
@media (max-width: 1360px) {
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

:deep(.el-table__row) {
  cursor: pointer;
}

/* 知识库 */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.kb-item {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 12px 14px;
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
