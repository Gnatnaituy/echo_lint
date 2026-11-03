import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发时后端地址：默认 8080，可用 VITE_API_TARGET 覆盖（例如 docker compose 把后端映射到 8082）
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 必须带结尾斜杠：Vite 默认按 startsWith 匹配，'/api' 会把 SPA 路由
      // /api-keys 也代理到后端（开发环境整页变 JSON 404）。
      '/api/': { target: apiTarget, changeOrigin: true },
      '/uploads/': { target: apiTarget, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        /**
         * 手动分包（Batch 4b，审计 P2-21 修正）：
         * - 只把第三方依赖拆成稳定 chunk 便于长期缓存；业务代码保持路由级动态 import（懒加载）
         * - i18n 词典被入口（App / 路由标题）与几乎所有路由共享，显式拆 `i18n` chunk：
         *   此前 i18n 被 Rollup 归入给 TrendChart 特判出来的 `charts` chunk，而入口又依赖 i18n，
         *   结果 charts 被写进 index.html 的 modulepreload（懒加载名不副实，charts 96.7% 是词典）
         * - 不再为 TrendChart.vue 单独建 charts chunk：它只被 Dashboard/Screen 懒加载，
         *   回落到 Rollup 默认的共享 chunk，首屏不会 modulepreload
         */
        manualChunks(id) {
          if (id.includes('/src/i18n/')) return 'i18n'
          if (!id.includes('node_modules')) return undefined
          if (id.includes('@element-plus/icons-vue')) return 'icons'
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('vue-router') || id.includes('/vue/') || id.includes('@vue/'))
            return 'vue'
          if (id.includes('axios')) return 'vendor'
          return 'vendor'
        }
      }
    }
  }
})
