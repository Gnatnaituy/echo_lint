<template>
  <div class="page stack">
    <!-- 概览 / 说明 -->
    <section class="panel">
      <div class="toolbar">
        <div class="row-wrap">
          <div class="section-title">{{ t('nav.tracing') }}</div>
          <!-- 深链目标：完整 traceId 放 title，胶囊只展示前 16 位避免撑破工具栏 -->
          <el-tooltip v-if="traceId" :content="traceId" placement="bottom">
            <span class="chip info mono">trace {{ shortTraceId }}</span>
          </el-tooltip>
          <span class="tiny dim trace-hint">{{ t('tracing.hint') }}</span>
        </div>
        <div class="actions">
          <el-button :icon="FullScreen" @click="openInNewWindow">
            {{ t('tracing.openNew') }}
          </el-button>
        </div>
      </div>
    </section>

    <!-- Jaeger 同域 iframe：nginx /jaeger/ 反代 + 登录态网关（frontend/nginx.conf）。
         同源资源（相对路径资产 / /jaeger/api/v3 接口）与平台会话 Cookie 自动携带。 -->
    <section class="panel frame-panel">
      <div v-if="loading" class="frame-loading">
        <el-icon class="is-loading" :size="22"><Loading /></el-icon>
        <span class="tiny dim">{{ t('tracing.loading') }}</span>
      </div>
      <iframe
        :key="frameSrc"
        :src="frameSrc"
        :title="t('nav.tracing')"
        class="trace-frame"
        @load="loading = false"
      ></iframe>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { FullScreen } from '@element-plus/icons-vue'
import { Loading } from '../components/icons.js'
import { useI18n } from '../i18n'

const { t } = useI18n()
const route = useRoute()

const loading = ref(true)

/**
 * 深链 traceId 只接受 32 位以内十六进制（任务视图透出的口径）：
 * query 是用户可控输入，非法值一律回退 Jaeger 搜索页，不进 iframe 的 src。
 */
const traceId = computed(() => {
  const raw = typeof route.query.trace === 'string' ? route.query.trace.trim() : ''
  return /^[0-9a-fA-F]{1,32}$/.test(raw) ? raw.toLowerCase() : ''
})

const frameSrc = computed(() =>
  traceId.value ? `/jaeger/trace/${traceId.value}` : '/jaeger/search'
)

const shortTraceId = computed(() =>
  traceId.value.length > 16 ? `${traceId.value.slice(0, 16)}…` : traceId.value
)

// 同页深链切换（列表按钮 → 另一条 trace）会重挂 iframe（:key），重新计 loading
watch(frameSrc, () => {
  loading.value = true
})

function openInNewWindow() {
  window.open(frameSrc.value, '_blank', 'noopener')
}
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
.row-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.trace-hint {
  line-height: 1.6;
}

/* Jaeger 全高内嵌：shell（14+18 外边距 + 22+34 内边距）+ 工具栏面板 + 栈间距 */
.frame-panel {
  position: relative;
  overflow: hidden;
}
.trace-frame {
  display: block;
  width: 100%;
  height: calc(100vh - 172px);
  min-height: 460px;
  border: 0;
  border-radius: 0 0 var(--r-lg) var(--r-lg);
}
.frame-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  background: var(--surface);
  z-index: 1;
}
</style>
