import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'

// https://vite.dev/config/
const apiProxyTarget = process.env.VITE_API_PROXY ?? 'http://localhost:8080'

/**
 * 管理端版本（docs/release.md）：构建时从 git 自动推导，Vite define 烘焙进包。
 * 显式环境变量（VITE_APP_VERSION / RELEASE_VERSION）仅给无 git 的 CI 构建用。
 */
function appVersion(): string {
  const override = process.env.VITE_APP_VERSION ?? process.env.RELEASE_VERSION
  if (override) return override
  try {
    const describe = execSync('git describe --tags --dirty --always', { encoding: 'utf8' }).trim()
    return describe.replace(/^v/, '') || '0.0.0-dev'
  } catch {
    return '0.0.0-dev'
  }
}

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(appVersion()),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
    // 避免 workspace 软链布局下出现多份 Vue 实例
    dedupe: ['vue'],
  },
  server: {
    port: 5173,
    // 开发期把 /api 转发到 Spring Boot（默认 8080）
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
})
