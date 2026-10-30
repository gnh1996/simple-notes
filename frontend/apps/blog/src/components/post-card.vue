<script setup lang="ts">
import { formatDate } from '../format'
import { postDate } from '../store'
import type { PostSummary } from '../types'

defineOptions({ name: 'BlogPostCard' })

defineProps<{ post: PostSummary }>()
</script>

<template>
  <article
    class="bc-card"
    data-blog-post-card
    :data-pinned="post.pinned ? '1' : '0'"
    :data-has-cover="post.coverUrl ? '1' : '0'"
  >
    <!-- 默认结构可被主题整体覆盖：<template #default="{ post }">…</template>（契约 §3.4） -->
    <slot :post="post">
      <a v-if="post.coverUrl" class="bc-card-cover" :href="'/posts/' + post.slug">
        <img :src="post.coverUrl" :alt="post.title" loading="lazy" />
      </a>
      <div class="bc-card-body">
        <div class="bc-card-meta">
          <span v-if="post.pinned" class="bc-card-pin">置顶</span>
          <time>{{ formatDate(postDate(post)) }}</time>
          <span>约 {{ post.readingMinutes }} 分钟</span>
        </div>
        <h2 class="bc-card-title"><a :href="'/posts/' + post.slug">{{ post.title }}</a></h2>
        <p class="bc-card-summary">{{ post.summary }}</p>
        <div v-if="post.tags && post.tags.length" class="bc-card-tags">
          <blog-tag-chip v-for="item in post.tags.slice(0, 3)" :key="item.id" :tag="item"></blog-tag-chip>
        </div>
      </div>
    </slot>
  </article>
</template>
