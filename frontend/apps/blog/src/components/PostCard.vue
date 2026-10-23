<script setup lang="ts">
import type { PublishedPostSummary } from '@simple-notes/api'

import { formatDate } from '@/utils/format'

defineProps<{ post: PublishedPostSummary }>()
</script>

<template>
  <article class="group overflow-hidden rounded-xl border border-gray-100 bg-white transition hover:border-brand-200 hover:shadow-sm">
    <RouterLink :to="`/posts/${post.slug}`" class="block">
      <img
        v-if="post.coverUrl"
        :src="post.coverUrl"
        :alt="post.title"
        class="h-44 w-full object-cover"
      />
    </RouterLink>
    <div class="p-5">
      <div class="flex flex-wrap items-center gap-2">
        <span
          v-if="post.pinned"
          class="rounded-full bg-amber-50 px-2 py-0.5 text-xs text-amber-600"
        >
          置顶
        </span>
        <span class="text-xs text-gray-400">{{ formatDate(post.firstPublishedAt) }}</span>
      </div>
      <RouterLink :to="`/posts/${post.slug}`" class="mt-2 block">
        <h2 class="text-lg font-semibold text-gray-900 group-hover:text-brand-600">
          {{ post.title }}
        </h2>
      </RouterLink>
      <p class="mt-2 line-clamp-2 text-sm leading-relaxed text-gray-600">{{ post.summary }}</p>
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
    </div>
  </article>
</template>
