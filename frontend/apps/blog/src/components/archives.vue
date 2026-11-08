<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '../api'
import { archives, archivesLoaded, listSort } from '../store'
import type { Archive } from '../types'

defineOptions({ name: 'BlogArchives' })

const rows = computed(() => archives.value)
const failed = ref(false)
const loading = ref(false)
const state = computed(() =>
  loading.value ? 'loading' : failed.value ? 'error' : rows.value.length ? 'ready' : 'empty',
)
const message = computed(() =>
  state.value === 'error' ? '归档加载失败' : state.value === 'empty' ? '暂无归档' : '',
)

onMounted(async () => {
  // 归档页已由路由守卫取好快照：直接复用
  if (archivesLoaded.value) {
    return
  }
  loading.value = true
  try {
    archives.value = await api<Archive[]>('/api/blog/archives', {
      direction: listSort.value.direction,
      sortBy: listSort.value.sortBy,
    })
    archivesLoaded.value = true
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <slot name="root" :archives="rows" :state="state" :message="message">
    <ul v-if="state === 'loading'" class="archive-index" aria-busy="true"></ul>
    <div v-else-if="state === 'error'" class="bc-state" data-state="error">{{ message }}</div>
    <div v-else-if="state === 'empty'" class="bc-state" data-state="empty">{{ message }}</div>
    <ul v-else class="archive-index">
      <li v-for="item in rows" :key="item.year + '-' + item.month">
        {{ item.year }} 年 {{ item.month }} 月 <span>{{ item.count }}</span>
      </li>
    </ul>
  </slot>
</template>
