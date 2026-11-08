<script setup lang="ts">
import { openLightbox } from '../lightbox'

defineOptions({ name: 'BlogImage' })

const props = withDefaults(defineProps<{ src: string; alt?: string }>(), { alt: '' })

function open(): void {
  openLightbox(props.src, props.alt)
}
</script>

<template>
  <slot name="root" :src="src" :alt="alt" :open="open">
    <button type="button" class="bc-image" @click="open">
      <img :src="src" :alt="alt" loading="lazy" />
    </button>
  </slot>
</template>
