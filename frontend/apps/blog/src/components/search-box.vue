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

function submit(): void {
  emit('search', text.value)
}
</script>

<template>
  <form class="bc-search" role="search" @submit.prevent="submit">
    <input v-model="text" type="search" placeholder="输入关键词" aria-label="搜索关键词" />
    <button type="submit">搜索</button>
  </form>
</template>
