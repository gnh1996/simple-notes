<script setup lang="ts">
import { ref, watch } from 'vue'

defineOptions({ name: 'BlogSearchBox' })

const props = withDefaults(defineProps<{ value?: string }>(), { value: '' })
const emit = defineEmits<{ search: [value: string] }>()

const text = ref(props.value)
watch(
  () => props.value,
  (next) => {
    text.value = next
  },
)

function search(value: string): void {
  emit('search', value)
}

function submit(): void {
  search(text.value)
}
</script>

<template>
  <slot name="root" :value="text" :search="search">
    <form class="bc-search" role="search" @submit.prevent="submit">
      <input v-model="text" type="search" placeholder="输入关键词" aria-label="搜索关键词" />
      <button type="submit">搜索</button>
    </form>
  </slot>
</template>
