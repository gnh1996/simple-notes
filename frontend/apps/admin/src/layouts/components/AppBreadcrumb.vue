<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 面包屑：首页 + 当前路由链（名称以服务端菜单为准） */
const items = computed(() => {
  const chain = route.matched
    .filter((record) => record.meta.title && !record.meta.public)
    .map((record) => record.meta.title as string)

  if (chain.length === 0) return ['首页']
  if (route.path === '/dashboard') return ['首页']
  return ['首页', ...chain]
})
</script>

<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item
      v-for="(title, index) in items"
      :key="`${index}-${title}`"
      :to="index === 0 && title === '首页' ? { path: '/dashboard' } : undefined"
    >
      <span class="truncate">{{ title }}</span>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>
