<script setup lang="ts">
defineOptions({ name: 'BlogTagChip' })

defineProps<{ tag: { id: string; name: string } }>()
</script>

<template>
  <a class="bc-tag" :href="'/tags/' + tag.id"># {{ tag.name }}</a>
</template>
