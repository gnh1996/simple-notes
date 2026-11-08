<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '../api'
import { tags, tagsLoaded } from '../store'
import type { Tag } from '../types'

defineOptions({ name: 'BlogTagCloud' })

const rows = computed(() => tags.value)
const failed = ref(false)
const loading = ref(false)
const state = computed(() =>
  loading.value ? 'loading' : failed.value ? 'error' : rows.value.length ? 'ready' : 'empty',
)
const message = computed(() =>
  state.value === 'error' ? '标签加载失败' : state.value === 'empty' ? '暂无标签' : '',
)

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
  <slot name="root" :tags="rows" :state="state" :message="message">
    <div v-if="state === 'error'" class="bc-state" data-state="error">{{ message }}</div>
    <div v-else-if="state === 'loading'" class="bc-state" data-state="loading" aria-busy="true">加载中…</div>
    <div v-else-if="state === 'empty'" class="bc-state" data-state="empty">{{ message }}</div>
    <div v-else class="bc-tag-cloud">
      <a v-for="item in rows" :key="item.id" class="bc-tag" :href="'/tags/' + item.id">
        {{ item.name }} <span class="bc-tag-count">{{ item.count }}</span>
      </a>
    </div>
  </slot>
</template>
