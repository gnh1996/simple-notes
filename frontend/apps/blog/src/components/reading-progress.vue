<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineOptions({ name: 'BlogReadingProgress' })

const progress = ref(0)

function onScroll(): void {
  const height = document.documentElement.scrollHeight - window.innerHeight
  progress.value = height > 0 ? Math.min(1, window.scrollY / height) : 0
}

onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
})
</script>

<template>
  <slot name="root" :progress="progress">
    <div class="bc-reading-progress" :style="{ transform: 'scaleX(' + progress + ')' }"></div>
  </slot>
</template>
