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
         * 手动分包（Batch 4b）：把不常变化的第三方依赖拆成独立 chunk，利于浏览器长期缓存；
         * 业务路由仍保持动态 import（懒加载），首屏只需 vue + element-plus 基础运行时。
         */
        manualChunks(id) {
          if (!id.includes('node_modules') && !id.includes('/src/components/TrendChart.vue')) {
            return undefined
          }
          if (id.includes('@element-plus/icons-vue')) return 'icons'
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('vue-router') || id.includes('/vue/') || id.includes('@vue/')) return 'vue'
          if (id.includes('TrendChart.vue')) return 'charts'
          if (id.includes('axios')) return 'vendor'
          return 'vendor'
        }
      }
    }
  }
})
