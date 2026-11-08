<script setup lang="ts">
defineOptions({ name: 'BlogState' })

withDefaults(defineProps<{ state?: string; message?: string }>(), { state: 'loading', message: '' })
</script>

<template>
  <slot name="root" :state="state" :message="message">
    <div class="bc-state" :data-state="state">
      <template v-if="state === 'loading'">
        <div class="bc-skeleton"></div>
        <div class="bc-skeleton" style="height: 96px"></div>
      </template>
      <template v-else-if="state === 'error'">{{ message || '加载失败，请稍后重试' }}</template>
      <template v-else-if="state === 'empty'">{{ message || '还没有内容' }}</template>
    </div>
  </slot>
</template>
