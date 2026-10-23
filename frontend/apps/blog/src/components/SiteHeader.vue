<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const links = [
  { to: '/', label: '首页' },
  { to: '/archives', label: '归档' },
  { to: '/tags', label: '标签' },
  { to: '/search', label: '搜索' },
  { to: '/about', label: '关于' },
]

const active = computed(() => {
  if (route.path === '/') return '/'
  return links.find((link) => link.to !== '/' && route.path.startsWith(link.to))?.to ?? ''
})
</script>

<template>
  <header class="sticky top-0 z-20 border-b border-gray-100 bg-white/90 backdrop-blur">
    <div class="mx-auto flex max-w-4xl items-center justify-between gap-4 px-4 py-4">
      <RouterLink to="/" class="shrink-0 text-lg font-bold text-brand-600">
        Simple Notes
      </RouterLink>
      <nav class="flex flex-wrap items-center justify-end gap-1 text-sm">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="rounded-md px-2.5 py-1.5 transition-colors"
          :class="active === link.to
            ? 'bg-brand-50 text-brand-700'
            : 'text-gray-500 hover:bg-gray-50 hover:text-gray-900'"
        >
          {{ link.label }}
        </RouterLink>
      </nav>
    </div>
  </header>
</template>
