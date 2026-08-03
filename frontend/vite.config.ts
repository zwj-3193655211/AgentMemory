import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
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
