import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ command }) => {
  const version = process.env.SERVER_WEB_VERSION
  if (version && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(version)) throw new Error('SERVER_WEB_VERSION 格式无效')
  return {
    plugins: [vue()], base: command === 'build' && version ? `/_web/schedule/${version}/` : '/schedule/',
    server: { port: 15173, strictPort: true, proxy: { '/schedule/api': 'http://127.0.0.1:18080', '/schedule/swagger-ui': 'http://127.0.0.1:18080' } }
  }
})
