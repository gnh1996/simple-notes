import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
const apiProxyTarget = process.env.VITE_API_PROXY ?? 'http://localhost:8080'

export default defineConfig({
  // 产物由后端壳以 `<asset-base>/runtime.js` 引用，块间相对路径即可
  base: './',
  plugins: [
    // 组件可以是 .vue（单文件组件试点）；主题模板仍走运行时编译，vue alias 不动
    vue(),
    {
      // 后端壳固定引用 /runtime.js；开发期把它接到 Vite 的 TS 入口
      name: 'blog-dev-runtime-entry',
      configureServer(server) {
        server.middlewares.use((request, _response, next) => {
          if (request.url === '/runtime.js' || request.url?.startsWith('/runtime.js?')) {
            request.url = '/src/runtime.ts'
          }
          next()
        })
      },
    },
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // 主题是运行时数据，必须保留模板编译器
      vue: 'vue/dist/vue.esm-bundler.js',
    },
    // 避免 workspace 软链布局下出现多份 Vue 实例
    dedupe: ['vue'],
  },
  define: {
    __VUE_OPTIONS_API__: 'true',
    __VUE_PROD_DEVTOOLS__: 'false',
    __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
  },
  server: {
    port: 5174,
    // 开发期把 /api 转发到 Spring Boot（默认 8080）
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        // 后端壳只认固定入口名；chunk / 静态资源仍带哈希
        entryFileNames: 'runtime.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
})
