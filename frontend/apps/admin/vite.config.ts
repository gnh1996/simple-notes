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
  // 容器部署时 admin 挂在同源 /admin/ 下（docker 内 nginx 托管）；本地开发保持 /
  base: process.env.VITE_ADMIN_BASE ?? '/',
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
  optimizeDeps: {
    // 懒加载路由里的第三方依赖（CodeMirror / Vditor）不在启动扫描范围内：
    // 首次打开页面才触发预构建，期间的请求会拿到 504 Outdated Optimize Dep，
    // 动态 import 失败后表现为“点菜单无响应”。这里显式预构建。
    include: [
      'vditor',
      '@codemirror/commands',
      '@codemirror/lang-css',
      '@codemirror/lang-html',
      '@codemirror/language',
      '@codemirror/state',
      '@codemirror/theme-one-dark',
      '@codemirror/view',
    ],
  },
  server: {
    port: 5173,
    // 开发期把 /api 转发到 Spring Boot（默认 8080）
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      // 主题预览：iframe 与 admin 同源，预览壳与 runtime 资源都走后端
      '/__preview': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/blog': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
})
