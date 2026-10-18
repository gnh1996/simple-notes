<script setup lang="ts">
import type { MenuNode } from '@simple-notes/api'
import { computed } from 'vue'

import { PAGE_REGISTRY } from '@/router/page-registry'

/**
 * 侧边栏菜单项（递归）：注册表命中 = 页面（可点击）；未命中 = 目录（仅分组，空目录照常显示）。
 * 名称 / 顺序 / 层级均来自服务端菜单树（docs/auth-rbac.md §6.2）。
 */
const props = defineProps<{ nodes: MenuNode[] }>()

const items = computed(() =>
  props.nodes.map((node) => {
    const page = PAGE_REGISTRY[node.code]
    return { node, path: page?.path, icon: page?.icon }
  }),
)
</script>

<template>
  <template v-for="item in items" :key="item.node.id">
    <!-- 页面型菜单 -->
    <el-menu-item v-if="item.path" :index="item.path">
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <template #title>{{ item.node.name }}</template>
    </el-menu-item>

    <!-- 目录型菜单（未命中注册表）：递归子节点 -->
    <el-sub-menu v-else :index="item.node.code">
      <template #title>
        <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
        <span class="truncate">{{ item.node.name }}</span>
      </template>
      <SidebarMenuItems :nodes="item.node.children ?? []" />
    </el-sub-menu>
  </template>
</template>
