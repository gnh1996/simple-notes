<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '../api'
import { tags, tagsLoaded } from '../store'
import type { Tag } from '../types'

defineOptions({ name: 'BlogTagCloud' })

const rows = computed(() => tags.value)
const failed = ref(false)
const loading = ref(false)

onMounted(async () => {
  // 标签页已由路由守卫取好快照：直接复用，避免二次请求与“暂无标签”闪烁
  if (tagsLoaded.value) {
    return
  }
  loading.value = true
  try {
    tags.value = await api<Tag[]>('/api/blog/tags')
    tagsLoaded.value = true
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="failed" class="bc-state" data-state="error">标签加载失败</div>
  <div v-else-if="loading" class="bc-tag-cloud" aria-busy="true"></div>
  <div v-else-if="!rows.length" class="bc-state" data-state="empty">暂无标签</div>
  <div v-else class="bc-tag-cloud">
    <a v-for="item in rows" :key="item.id" class="bc-tag" :href="'/tags/' + item.id">
      {{ item.name }} <span class="bc-tag-count">{{ item.count }}</span>
    </a>
  </div>
</template>
