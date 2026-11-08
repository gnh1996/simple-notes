<script setup lang="ts">
import { computed } from 'vue'

import type { Pager } from '../types'

defineOptions({ name: 'BlogPager' })

const props = defineProps<{ pager: Pager }>()
const emit = defineEmits<{ go: [page: number] }>()

const pages = computed(() => Array.from({ length: props.pager.totalPages }, (_, i) => i + 1))

function go(page: number): void {
  emit('go', page)
}
</script>

<template>
  <slot name="root" :pager="pager" :go="go" :pages="pages">
    <nav v-if="pager.totalPages > 1" class="bc-pager" aria-label="分页">
      <button type="button" :disabled="!pager.hasPrev" @click="go(pager.page - 1)">上一页</button>
      <span>第 {{ pager.page }} / {{ pager.totalPages }} 页</span>
      <button type="button" :disabled="!pager.hasNext" @click="go(pager.page + 1)">下一页</button>
    </nav>
  </slot>
</template>
