<script setup lang="ts">
import { colorMode, toggleColorMode } from '../store'

defineOptions({ name: 'BlogThemeToggle' })
</script>

<template>
  <button
    type="button"
    class="bc-theme-toggle"
    :aria-label="colorMode === 'dark' ? '切换到浅色' : '切换到深色'"
    @click="toggleColorMode"
  >
    <svg
      v-if="colorMode === 'dark'"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
    >
      <circle cx="12" cy="12" r="4"></circle>
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"></path>
    </svg>
    <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
      <path d="M21 12.8A8.5 8.5 0 1 1 11.2 3a6.6 6.6 0 0 0 9.8 9.8Z"></path>
    </svg>
  </button>
</template>
