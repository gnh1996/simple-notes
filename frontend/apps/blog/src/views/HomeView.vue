<script setup lang="ts">
interface PostItem {
  id: number
  title: string
  summary: string
  date: string
}

const posts: PostItem[] = [
  {
    id: 1,
    title: '用 pnpm workspace 组织管理端与博客',
    summary: '两个 Vue 应用共享 API 客户端与设计 token，独立构建、独立部署。',
    date: '2026-10-16',
  },
  {
    id: 2,
    title: '响应式不是做两套页面',
    summary: '路由与逻辑只有一份，只在断点处切换展示层——表格与卡片列表共享同一数据源。',
    date: '2026-10-13',
  },
  {
    id: 3,
    title: 'Spring Boot + PostgreSQL 的随手记后端',
    summary: 'JPA + Flyway 管理 schema 演进，springdoc-openapi 为多端生成类型化客户端。',
    date: '2026-10-10',
  },
]
</script>

<template>
  <div class="min-h-screen bg-white">
    <header class="border-b border-gray-100">
      <div class="mx-auto flex max-w-3xl items-center justify-between px-4 py-6">
        <span class="text-xl font-bold text-brand-600">Simple Notes</span>
        <span class="text-sm text-gray-400">随手记 · 博客</span>
      </div>
    </header>

    <main class="mx-auto max-w-3xl px-4 py-8">
      <h1 class="text-2xl font-bold md:text-3xl">最新文章</h1>

      <!-- 移动优先的单栏，逐级放大到双栏 -->
      <div class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        <article
          v-for="post in posts"
          :key="post.id"
          class="rounded-lg border border-gray-100 p-4 transition hover:border-brand-300 hover:shadow-sm"
        >
          <h2 class="font-semibold">{{ post.title }}</h2>
          <p class="mt-2 line-clamp-2 text-sm leading-relaxed text-gray-600">{{ post.summary }}</p>
          <p class="mt-3 text-xs text-gray-400">{{ post.date }}</p>
        </article>
      </div>
    </main>
  </div>
</template>
