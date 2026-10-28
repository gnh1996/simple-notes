<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { renderMarkdown } from '../markdown'
import { colorMode } from '../store'

defineOptions({ name: 'BlogMarkdown' })

const props = withDefaults(defineProps<{ source?: string }>(), { source: '' })

const el = ref<HTMLElement | null>(null)
let sequence = 0

async function draw(): Promise<void> {
  if (!el.value) {
    return
  }
  const current = ++sequence
  await renderMarkdown(el.value, props.source, colorMode.value === 'dark' ? 'dark' : 'light')
  if (current !== sequence) {
    return
  }
  window.dispatchEvent(new CustomEvent('blog:markdown-rendered'))
}

onMounted(draw)
watch([() => props.source, colorMode], draw, { flush: 'post' })
onBeforeUnmount(() => {
  sequence++
})
</script>

<template>
  <div ref="el" class="bc-prose" data-blog-prose></div>
</template>
