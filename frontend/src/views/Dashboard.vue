<template>
  <div>
    <!-- 统计卡片 -->
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card shadow="hover" :class="['stat-card', card.cls]">
          <div class="stat-value">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-16">
      <!-- 近7日上传趋势 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>近 7 日上传趋势</template>
          <div class="bar-chart">
            <div v-for="d in overview?.last7Days || []" :key="d.date" class="bar-item">
              <div class="bar-value" v-if="d.count > 0">{{ d.count }}</div>
              <div
                class="bar"
                :style="{ height: barHeight(d.count) + 'px' }"
                :class="{ zero: d.count === 0 }"
              ></div>
              <div class="bar-label">{{ d.date.slice(5) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <!-- 词典与语料 -->
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>词典与语料</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="敏感词总数">{{ overview?.dictionary?.total ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="启用中">{{ overview?.dictionary?.enabled ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="AI 挖掘待审核">
              <router-link to="/dictionary?source=MINED&enabled=false" class="link">
                {{ overview?.dictionary?.minedPending ?? 0 }}
              </router-link>
            </el-descriptions-item>
            <el-descriptions-item label="语料(违/合规)">
              {{ overview?.corpus?.violation ?? 0 }} / {{ overview?.corpus?.compliant ?? 0 }}
            </el-descriptions-item>
          </el-descriptions>
          <div class="mt-16 hint">
            语料库来自人工复检结论，自动注入 AI 复筛的 few-shot 提示词；
            确认违规的录音会自动挖掘新敏感词（停用待审核）。
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近录音 -->
    <el-card shadow="never" class="mt-16">
      <template #header>
        <div class="card-header">
          <span>最近录音</span>
          <router-link to="/recordings">
            <el-button type="primary" size="small" link>前往录音管理 →</el-button>
          </router-link>
        </div>
      </template>
      <el-table :data="recent" size="small" empty-text="暂无录音，去录音管理上传">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column label="时长" width="90">
          <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="命中词" width="80" align="center">
          <template #default="{ row }">{{ row.hitCount }}</template>
        </el-table-column>
        <el-table-column label="复筛结论" width="120" align="center">
          <template #default="{ row }">{{ row.violationTypeLabel || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="上传时间" width="160">
          <template #default="{ row }">{{ formatTime(row.uploadTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import StatusTag from '../components/StatusTag.vue'
import { formatDuration, formatTime } from '../constants'

const router = useRouter()
const overview = ref(null)
const recent = ref([])

const cards = computed(() => {
  const s = overview.value?.statusCounts || {}
  return [
    { label: '录音总数', value: overview.value?.totalUploads ?? '-', cls: '' },
    { label: '今日新增', value: overview.value?.todayUploads ?? '-', cls: '' },
    { label: '待人工复检', value: s.NEEDS_REVIEW ?? 0, cls: 'danger', action: () => router.push('/review') },
    { label: '确认违规', value: s.VIOLATION_CONFIRMED ?? 0, cls: 'danger' },
    { label: '自动通过', value: s.COMPLIANT ?? 0, cls: 'success' },
    { label: '处理失败', value: s.FAILED ?? 0, cls: 'danger' }
  ]
})

function barHeight(count) {
  const max = Math.max(1, ...(overview.value?.last7Days || []).map((d) => d.count))
  return Math.max(2, Math.round((count / max) * 90))
}

async function load() {
  try {
    const [ov, list] = await Promise.all([
      api.overview(),
      api.listRecordings({ page: 0, size: 8 })
    ])
    overview.value = ov
    recent.value = list.content || []
  } catch {
    // 后端未就绪
  }
}

onMounted(load)
</script>

<style scoped>
.stat-card {
  text-align: center;
  cursor: pointer;
}
.stat-card :deep(.el-card__body) {
  padding: 18px 12px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
}
.stat-card.danger .stat-value {
  color: #f56c6c;
}
.stat-card.success .stat-value {
  color: #67c23a;
}
.stat-label {
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
}
.mt-16 {
  margin-top: 16px;
}
.bar-chart {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 140px;
  padding: 0 8px;
}
.bar-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
  gap: 4px;
}
.bar {
  width: 34px;
  background: #409eff;
  border-radius: 4px 4px 0 0;
}
.bar.zero {
  background: #dcdfe6;
}
.bar-value {
  font-size: 12px;
  color: #606266;
}
.bar-label {
  font-size: 12px;
  color: #909399;
}
.hint {
  color: #909399;
  font-size: 13px;
  line-height: 1.7;
}
.link {
  color: #409eff;
  text-decoration: none;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>