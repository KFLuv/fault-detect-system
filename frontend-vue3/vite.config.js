import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发模式：npm run dev 后访问 http://localhost:5173
// /api 请求代理到后端 8000 端口，无需处理跨域
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8000',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    emptyOutDir: true
  }
})
