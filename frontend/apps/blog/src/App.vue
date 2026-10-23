<template>
  <div class="min-h-screen bg-white text-gray-900">
    <SiteHeader />
    <main class="mx-auto max-w-4xl px-4 py-8">
      <RouterView />
    </main>
    <footer class="border-t border-gray-100">
      <div class="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-2 px-4 py-6 text-xs text-gray-400">
        <span>Simple Notes · 随手记与博客</span>
        <span>Powered by Spring Boot + Vue</span>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import SiteHeader from '@/components/SiteHeader.vue'
</script>
