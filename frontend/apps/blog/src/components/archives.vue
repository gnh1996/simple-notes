<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '../api'
import { archives, archivesLoaded } from '../store'
import type { Archive } from '../types'

defineOptions({ name: 'BlogArchives' })

const rows = computed(() => archives.value)
const failed = ref(false)
const loading = ref(false)

onMounted(async () => {
  // 归档页已由路由守卫取好快照：直接复用
  if (archivesLoaded.value) {
    return
  }
  loading.value = true
  try {
    archives.value = await api<Archive[]>('/api/blog/archives')
    archivesLoaded.value = true
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <ul v-if="loading" class="archive-index" aria-busy="true"></ul>
  <div v-else-if="failed" class="bc-state" data-state="error">归档加载失败</div>
  <div v-else-if="!rows.length" class="bc-state" data-state="empty">暂无归档</div>
  <ul v-else class="archive-index">
    <li v-for="item in rows" :key="item.year + '-' + item.month">
      {{ item.year }} 年 {{ item.month }} 月 <span>{{ item.count }}</span>
    </li>
  </ul>
</template>
