<script setup lang="ts">
import { HomeFilled } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

import SidebarMenuItems from './SidebarMenuItems.vue'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const auth = useAuthStore()
</script>

<template>
  <div class="flex h-full flex-col bg-[var(--el-bg-color)]">
    <!-- 品牌区 -->
    <div
      class="flex h-14 shrink-0 items-center gap-2 overflow-hidden border-b border-[var(--el-border-color-lighter)] px-4"
    >
      <img src="/favicon.svg" alt="Simple Notes" class="h-7 w-7 shrink-0" />
      <span
        v-show="!collapsed"
        class="truncate text-base font-semibold text-[var(--el-text-color-primary)]"
      >
        Simple Notes
      </span>
    </div>

    <el-scrollbar class="min-h-0 flex-1">
      <!-- 侧边栏 = 首页（固定）+ 服务端菜单树（名称 / 顺序 / 层级以 sys_menu 为准） -->
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        class="app-sidebar-menu"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon>
          <template #title>首页</template>
        </el-menu-item>
        <SidebarMenuItems :nodes="auth.menus" />
      </el-menu>
    </el-scrollbar>
  </div>
</template>
