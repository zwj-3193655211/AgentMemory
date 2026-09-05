import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  // 强制锁定 API 为相对路径：vite 的 import.meta.env 会被进程环境变量覆盖（优先级高于 .env 文件），
  // 若终端残留旧的 VITE_API_BASE=http://localhost:8082/api，会导致跨域 "Network Error"。
  // 本项目 API 始终同源/经代理，固定为相对路径最安全。
  define: {
    'import.meta.env.VITE_API_BASE': '"/api"',
    'import.meta.env.VITE_EMBED_BASE': '"/api"'
  },
  build: {
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: (id: string) => {
          if (id.includes('node_modules/echarts') || id.includes('node_modules/vue-echarts')) return 'echarts'
          if (id.includes('node_modules/element-plus')) return 'element-plus'
          if (id.includes('node_modules/vue') || id.includes('node_modules/axios')) return 'vendor'
          return undefined
        }
      }
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5175,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true
      }
    }
  }
})
