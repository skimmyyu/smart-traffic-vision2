import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 监听所有网卡，局域网其它电脑才能连入
    host: '0.0.0.0',
    strictPort: true,
    // 开发态经 Vite 转发，其它电脑只需放行 5173 端口
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/ws': {
        target: 'http://127.0.0.1:8080',
        ws: true,
        changeOrigin: true
      },
      '/hls-proxy': {
        target: 'http://127.0.0.1:8888',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/hls-proxy/, '')
      },
      '/webrtc-proxy': {
        target: 'http://127.0.0.1:8889',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/webrtc-proxy/, '')
      }
    }
  }
})
