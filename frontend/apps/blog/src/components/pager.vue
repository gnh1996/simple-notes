<script setup lang="ts">
import type { Pager } from '../types'

defineOptions({ name: 'BlogPager' })

defineProps<{ pager: Pager }>()
defineEmits<{ go: [page: number] }>()
</script>

<template>
  <nav v-if="pager.totalPages > 1" class="bc-pager" aria-label="分页">
    <button type="button" :disabled="!pager.hasPrev" @click="$emit('go', pager.page - 1)">上一页</button>
    <span>第 {{ pager.page }} / {{ pager.totalPages }} 页</span>
    <button type="button" :disabled="!pager.hasNext" @click="$emit('go', pager.page + 1)">下一页</button>
  </nav>
</template>
