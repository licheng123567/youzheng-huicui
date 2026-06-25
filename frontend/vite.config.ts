import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端从契约生成客户端，开发期把 /v1 代理到后端横切层（mvn spring-boot:run on 9091）。
// 后端未起时可改代理到 Prism mock（4010）实现"对 mock 开发"。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/v1': { target: 'http://localhost:9091', changeOrigin: true },
    },
  },
})
