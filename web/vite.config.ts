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
  // 会话身份（WK17）：前端向 /api/identity/me 询问「我是谁」，答案由服务端从会话解析，
  // 不再由构建期变量把权限主体交给客户端。
  if (env.WECOM_SYNC_URL) {
    proxy['/api/identity'] = {
      target: env.WECOM_SYNC_URL,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, ''),
    }
  }
  // 平台管理面入口（WK12）。留空则不代理（未接入 WeKnora 的环境）。
  // 注意：WeKnora 是 SPA，其 index.html 引用的是绝对路径资源（/assets/…、/api/…），
  // 反代到子路径时这些请求不会带 /weknora 前缀，会打到本前端而 404，
  // 所以此规则仅在 WeKnora 支持配置 base path 时才有意义；
  // 首期入口按「独立地址 + 各自登录」使用（统一登录排在 WK17 的 OIDC）。
  if (env.WEKNORA_CONSOLE_URL) {
    proxy['/weknora'] = {
      target: env.WEKNORA_CONSOLE_URL,
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/weknora/, ''),
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
