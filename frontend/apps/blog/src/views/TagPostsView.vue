<script setup lang="ts">
import type { PublishedPostSummary, TagCountResponse } from '@simple-notes/api'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { blogApi } from '@/api'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const loading = ref(false)
const posts = ref<PublishedPostSummary[]>([])
const tag = ref<TagCountResponse | null>(null)
const error = ref('')

onMounted(async () => {
  loading.value = true
  try {
    const tagId = String(route.params.id)
    const [tags, result] = await Promise.all([
      blogApi.tags(),
      blogApi.list({ page: 1, size: 100, tagId }),
    ])
    tag.value = tags.find((item) => item.id === tagId) ?? null
    posts.value = result.items ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : '标签文章加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <RouterLink to="/tags" class="text-sm text-gray-400 hover:text-brand-600">← 全部标签</RouterLink>
    <h1 class="mt-4 text-2xl font-bold tracking-tight text-gray-900">
      # {{ tag?.name || '标签' }}
    </h1>
    <p class="mt-2 text-sm text-gray-500">{{ tag?.count ?? posts.length }} 篇文章</p>

    <div v-if="loading" class="mt-6 grid gap-4 md:grid-cols-2">
      <div v-for="i in 4" :key="i" class="h-60 animate-pulse rounded-xl bg-gray-100" />
    </div>
    <div v-else-if="error" class="mt-6 text-sm text-red-500">{{ error }}</div>
    <div v-else-if="posts.length" class="mt-6 grid gap-4 md:grid-cols-2">
      <PostCard v-for="post in posts" :key="post.slug" :post="post" />
    </div>
    <div v-else class="mt-6 text-sm text-gray-400">该标签下暂无文章</div>
  </section>
</template>
