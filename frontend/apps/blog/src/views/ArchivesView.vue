<script setup lang="ts">
import type { ArchiveResponse, PublishedPostSummary } from '@simple-notes/api'
import { onMounted, ref } from 'vue'

import { blogApi } from '@/api'
import PostCard from '@/components/PostCard.vue'

const loading = ref(false)
const archives = ref<ArchiveResponse[]>([])
const posts = ref<PublishedPostSummary[]>([])
const selected = ref<ArchiveResponse | null>(null)
const error = ref('')

onMounted(async () => {
  loading.value = true
  try {
    archives.value = (await blogApi.archives()) ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : '归档加载失败'
  } finally {
    loading.value = false
  }
})

async function select(archive: ArchiveResponse): Promise<void> {
  selected.value = archive
  loading.value = true
  try {
    const result = await blogApi.list({
      page: 1,
      size: 100,
      year: archive.year,
      month: archive.month,
    })
    posts.value = result.items ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : '归档文章加载失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section>
    <h1 class="text-2xl font-bold tracking-tight text-gray-900">归档</h1>
    <p class="mt-2 text-sm text-gray-500">按发布时间浏览文章。</p>

    <div v-if="error" class="mt-6 text-sm text-red-500">{{ error }}</div>
    <div v-else-if="archives.length === 0 && !loading" class="mt-6 text-sm text-gray-400">暂无归档</div>
    <div v-else class="mt-6 space-y-2">
      <button
        v-for="archive in archives"
        :key="`${archive.year}-${archive.month}`"
        class="flex w-full items-center justify-between rounded-lg border px-4 py-3 text-left text-sm transition"
        :class="selected === archive
          ? 'border-brand-200 bg-brand-50 text-brand-700'
          : 'border-gray-100 hover:border-brand-200 hover:bg-gray-50'"
        @click="select(archive)"
      >
        <span>{{ archive.year }} 年 {{ archive.month }} 月</span>
        <span class="text-xs text-gray-400">{{ archive.count }} 篇</span>
      </button>
    </div>

    <div v-if="selected" class="mt-8">
      <h2 class="text-lg font-semibold text-gray-900">
        {{ selected.year }} 年 {{ selected.month }} 月
      </h2>
      <div v-if="loading" class="mt-4 grid gap-4 md:grid-cols-2">
        <div v-for="i in 2" :key="i" class="h-60 animate-pulse rounded-xl bg-gray-100" />
      </div>
      <div v-else class="mt-4 grid gap-4 md:grid-cols-2">
        <PostCard v-for="post in posts" :key="post.slug" :post="post" />
      </div>
    </div>
  </section>
</template>
