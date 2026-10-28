<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineOptions({ name: 'BlogBackToTop' })

const visible = ref(false)

function onScroll(): void {
  visible.value = window.scrollY > 640
}

function scrollToTop(): void {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <button
    type="button"
    class="bc-back-to-top"
    :data-visible="visible ? '1' : '0'"
    aria-label="回到顶部"
    @click="scrollToTop"
  >
    ↑
  </button>
</template>
