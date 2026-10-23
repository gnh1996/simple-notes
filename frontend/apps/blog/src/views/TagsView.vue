<script setup lang="ts">
import type { TagCountResponse } from '@simple-notes/api'
import { onMounted, ref } from 'vue'

import { blogApi } from '@/api'

const loading = ref(false)
const tags = ref<TagCountResponse[]>([])
const error = ref('')

onMounted(async () => {
  loading.value = true
  try {
    tags.value = (await blogApi.tags()) ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : '标签加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section>
    <h1 class="text-2xl font-bold tracking-tight text-gray-900">标签</h1>
    <p class="mt-2 text-sm text-gray-500">按主题浏览已发布文章。</p>

    <div v-if="loading" class="mt-6 flex flex-wrap gap-2">
      <div v-for="i in 8" :key="i" class="h-8 w-20 animate-pulse rounded-full bg-gray-100" />
    </div>
    <div v-else-if="error" class="mt-6 text-sm text-red-500">{{ error }}</div>
    <div v-else-if="tags.length" class="mt-6 flex flex-wrap gap-2">
      <RouterLink
        v-for="tag in tags"
        :key="tag.id"
        :to="`/tags/${tag.id}`"
        class="rounded-full border border-gray-200 px-4 py-2 text-sm text-gray-600 transition hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700"
      >
        {{ tag.name }}
        <span class="ml-1 text-xs text-gray-400">{{ tag.count }}</span>
      </RouterLink>
    </div>
    <div v-else class="mt-6 text-sm text-gray-400">暂无标签</div>
  </section>
</template>
