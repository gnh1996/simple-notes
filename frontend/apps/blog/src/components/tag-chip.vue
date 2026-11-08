<script setup lang="ts">
defineOptions({ name: 'BlogTagChip' })

defineProps<{ tag: { id: string; name: string } }>()
</script>

<template>
  <slot name="root" :tag="tag">
    <a class="bc-tag" :href="'/tags/' + tag.id"># {{ tag.name }}</a>
  </slot>
</template>
