<script setup lang="ts">
import type { PublishedPostSummary } from '@simple-notes/api'
import { onMounted, ref } from 'vue'

import { blogApi } from '@/api'
import PostCard from '@/components/PostCard.vue'

const loading = ref(false)
const posts = ref<PublishedPostSummary[]>([])
const page = ref(1)
const size = 10
const total = ref(0)
const error = ref('')

async function load(nextPage = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await blogApi.list({ page: nextPage, size })
    posts.value = result.items ?? []
    total.value = result.total ?? 0
    page.value = nextPage
  } catch (err) {
    error.value = err instanceof Error ? err.message : '文章加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))

function go(nextPage: number): void {
  if (nextPage < 1 || nextPage > Math.max(1, Math.ceil(total.value / size))) return
  void load(nextPage)
}
</script>

<template>
  <section class="pb-8">
    <p class="text-xs font-medium uppercase tracking-[0.2em] text-brand-500">Simple Notes</p>
    <h1 class="mt-3 text-3xl font-bold tracking-tight text-gray-900 md:text-4xl">
      随手记，也认真写博客
    </h1>
    <p class="mt-3 max-w-2xl text-sm leading-relaxed text-gray-500">
      记录日常、整理思考，偶尔把值得分享的内容发布出来。
    </p>
  </section>

  <section>
    <div v-if="loading" class="grid gap-4 md:grid-cols-2">
      <div v-for="i in 4" :key="i" class="h-64 animate-pulse rounded-xl bg-gray-100" />
    </div>

    <div v-else-if="error" class="rounded-xl border border-red-100 bg-red-50 p-6 text-sm text-red-600">
      {{ error }}
    </div>

    <div v-else-if="posts.length" class="grid gap-4 md:grid-cols-2">
      <PostCard v-for="post in posts" :key="post.slug" :post="post" />
    </div>

    <div v-else class="rounded-xl border border-dashed border-gray-200 p-12 text-center text-sm text-gray-400">
      还没有发布文章
    </div>

    <div v-if="total > size" class="mt-8 flex items-center justify-center gap-3 text-sm">
      <button
        class="rounded-md border border-gray-200 px-3 py-1.5 text-gray-600 disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="page <= 1"
        @click="go(page - 1)"
      >
        上一页
      </button>
      <span class="text-xs text-gray-400">第 {{ page }} / {{ Math.ceil(total / size) }} 页</span>
      <button
        class="rounded-md border border-gray-200 px-3 py-1.5 text-gray-600 disabled:cursor-not-allowed disabled:opacity-40"
        :disabled="page >= Math.ceil(total / size)"
        @click="go(page + 1)"
      >
        下一页
      </button>
    </div>
  </section>
</template>
