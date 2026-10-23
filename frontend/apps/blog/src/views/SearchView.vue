<script setup lang="ts">
import type { PublishedPostSummary } from '@simple-notes/api'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { blogApi } from '@/api'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()
const keyword = ref(String(route.query.q ?? ''))
const loading = ref(false)
const posts = ref<PublishedPostSummary[]>([])
const searched = ref(false)

async function search(): Promise<void> {
  const q = keyword.value.trim()
  if (!q) return
  searched.value = true
  loading.value = true
  try {
    const result = await blogApi.search({ q, page: 1, size: 50 })
    posts.value = result.items ?? []
    await router.replace({ path: '/search', query: { q } })
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (keyword.value) void search()
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-bold tracking-tight text-gray-900">搜索</h1>
    <p class="mt-2 text-sm text-gray-500">搜索已发布文章的标题与正文。</p>

    <form class="mt-6 flex gap-2" @submit.prevent="search">
      <input
        v-model="keyword"
        type="search"
        placeholder="输入关键词"
        class="min-w-0 flex-1 rounded-lg border border-gray-200 px-4 py-2 text-sm outline-none transition focus:border-brand-400"
      />
      <button
        type="submit"
        class="rounded-lg bg-brand-600 px-5 py-2 text-sm font-medium text-white transition hover:bg-brand-700"
      >
        搜索
      </button>
    </form>

    <div v-if="loading" class="mt-6 grid gap-4 md:grid-cols-2">
      <div v-for="i in 4" :key="i" class="h-60 animate-pulse rounded-xl bg-gray-100" />
    </div>
    <div v-else-if="searched && posts.length === 0" class="mt-8 text-sm text-gray-400">
      没有找到匹配的文章
    </div>
    <div v-else-if="posts.length" class="mt-8 grid gap-4 md:grid-cols-2">
      <PostCard v-for="post in posts" :key="post.slug" :post="post" />
    </div>
  </section>
</template>
