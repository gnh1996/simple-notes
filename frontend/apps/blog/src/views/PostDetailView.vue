<script setup lang="ts">
import type { PublishedPostDetail } from '@simple-notes/api'
import { markdownCommonProps } from '@simple-notes/shared'
import { MdCatalog, MdPreview } from 'md-editor-v3'
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { blogApi } from '@/api'
import { formatDate, readingMinutes } from '@/utils/format'

const route = useRoute()
const loading = ref(false)
const post = ref<PublishedPostDetail | null>(null)
const error = ref('')

async function load(slug: string): Promise<void> {
  loading.value = true
  error.value = ''
  post.value = null
  try {
    post.value = await blogApi.detail(slug)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '文章加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => load(String(route.params.slug)))
watch(
  () => route.params.slug,
  (slug) => {
    if (slug) void load(String(slug))
  },
)
</script>

<template>
  <div v-if="loading" class="space-y-4">
    <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100" />
    <div class="h-4 w-1/3 animate-pulse rounded bg-gray-100" />
    <div class="h-96 animate-pulse rounded-xl bg-gray-100" />
  </div>

  <div v-else-if="error" class="rounded-xl border border-dashed border-gray-200 p-12 text-center">
    <p class="text-lg font-medium text-gray-700">文章不存在或尚未发布</p>
    <p class="mt-2 text-sm text-gray-400">{{ error }}</p>
    <RouterLink to="/" class="mt-4 inline-block text-sm text-brand-600 hover:underline">
      返回首页
    </RouterLink>
  </div>

  <article v-else-if="post" class="grid gap-8 lg:grid-cols-[minmax(0,1fr)_200px]">
    <div class="min-w-0">
      <RouterLink to="/" class="text-sm text-gray-400 hover:text-brand-600">← 返回文章列表</RouterLink>
      <h1 class="mt-4 text-3xl font-bold leading-tight tracking-tight text-gray-900">
        {{ post.title }}
      </h1>
      <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-gray-400">
        <span>{{ post.authorName }}</span>
        <span>{{ formatDate(post.firstPublishedAt) }}</span>
        <span>约 {{ readingMinutes(post.content) }} 分钟</span>
      </div>
      <div class="mt-3 flex flex-wrap gap-1.5">
        <RouterLink
          v-for="tag in post.tags"
          :key="tag.id"
          :to="`/tags/${tag.id}`"
          class="rounded-full bg-gray-50 px-2 py-0.5 text-xs text-gray-500 hover:bg-brand-50 hover:text-brand-600"
        >
          # {{ tag.name }}
        </RouterLink>
      </div>

      <img
        v-if="post.coverUrl"
        :src="post.coverUrl"
        :alt="post.title"
        class="mt-6 max-h-96 w-full rounded-xl object-cover"
      />

      <div class="markdown-body mt-6">
        <MdPreview
          id="post-preview"
          :model-value="post.content"
          v-bind="markdownCommonProps"
        />
      </div>
    </div>

    <aside class="hidden lg:block">
      <div class="sticky top-24">
        <p class="mb-2 text-xs font-medium uppercase tracking-wider text-gray-400">目录</p>
        <MdCatalog
          editor-id="post-preview"
          sync-with="preview"
          :offset-top="88"
          class="max-h-[70vh] overflow-auto text-sm"
        />
      </div>
    </aside>
  </article>
</template>
