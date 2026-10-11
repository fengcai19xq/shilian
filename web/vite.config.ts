/// <reference types="vitest" />
import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

// 开发代理：/api/decide、/api/retrieval、/api/gateway 分别转发到环境变量指定的服务地址
function buildProxy(env: Record<string, string>): Record<string, ProxyOptions> {
  const proxy: Record<string, ProxyOptions> = {}
  if (env.DECIDE_URL) {
    proxy['/api/decide'] = {
      target: env.DECIDE_URL,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, ''),
    }
  }
  if (env.RETRIEVAL_URL) {
    proxy['/api/retrieval'] = {
      target: env.RETRIEVAL_URL,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, ''),
    }
  }
  if (env.GATEWAY_URL) {
    proxy['/api/gateway'] = {
      target: env.GATEWAY_URL,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api\/gateway/, ''),
    }
  }
  return proxy
}

// 工作台前端：开发服务器 + vitest（jsdom）配置
export default defineConfig(({ mode }) => {
  // envDir 为 web/（vite 在 web/ 下运行）；第三个参数为空串：同时读取不带 VITE_ 前缀的服务地址（只在 Node 侧使用，不打进前端产物）
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: { port: 5173, proxy: buildProxy(env) },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: true,
    },
  }
})
