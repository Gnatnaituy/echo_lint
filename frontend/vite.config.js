import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发时后端地址：默认 8080，可用 VITE_API_TARGET 覆盖（例如 docker compose 把后端映射到 8082）
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/uploads': { target: apiTarget, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200
  }
})
