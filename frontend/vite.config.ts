import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()], base: '/schedule/',
  server: { port: 15173, strictPort: true, proxy: { '/schedule/api': 'http://127.0.0.1:18080', '/schedule/swagger-ui': 'http://127.0.0.1:18080' } }
})
