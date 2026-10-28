<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { contentVersion, currentPath } from '../store'

defineOptions({ name: 'BlogToc' })

const items = ref<Array<{ id: string; text: string; depth: number }>>([])
const activeId = ref('')
let observer: IntersectionObserver | null = null

async function build(): Promise<void> {
  await nextTick()
  const headings = [...document.querySelectorAll<HTMLElement>('.bc-prose h2, .bc-prose h3')]
  items.value = headings.map((heading) => ({
    id: heading.id,
    text: heading.textContent || '',
    depth: heading.tagName === 'H3' ? 3 : 2,
  }))
  if (observer) {
    observer.disconnect()
  }
  if (!headings.length) {
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (visible.length) {
        activeId.value = (visible[0].target as HTMLElement).id
      }
    },
    { rootMargin: '-88px 0px -70% 0px', threshold: 0 },
  )
  headings.forEach((heading) => observer?.observe(heading))
}

watch([contentVersion, currentPath], build, { immediate: true })

function onRendered(): void {
  void build()
}

onMounted(() => window.addEventListener('blog:markdown-rendered', onRendered))
onBeforeUnmount(() => {
  window.removeEventListener('blog:markdown-rendered', onRendered)
  observer?.disconnect()
})

function jump(event: Event, id: string): void {
  event.preventDefault()
  const target = document.getElementById(id)
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
    history.replaceState(null, '', `#${id}`)
  }
}
</script>

<template>
  <ul class="bc-toc">
    <li v-for="item in items" :key="item.id" :class="'depth-' + item.depth">
      <a
        :href="'#' + item.id"
        :class="{ active: activeId === item.id }"
        @click="jump($event, item.id)"
        >{{ item.text }}</a
      >
    </li>
  </ul>
</template>
