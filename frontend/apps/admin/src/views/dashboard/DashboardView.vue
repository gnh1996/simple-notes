<script setup lang="ts">
import { computed } from 'vue'

import { PAGE_REGISTRY } from '@/router/page-registry'
import { useAuthStore } from '@/stores/auth'
import type { MenuNode } from '@simple-notes/api'

const auth = useAuthStore()

const avatarText = computed(() => auth.displayName.slice(0, 1).toUpperCase() || 'U')

/** 快捷入口 = 当前可见菜单（服务端过滤）× 页面注册表（有页面的才可点击进入） */
const quickLinks = computed(() => {
  const links: Array<{ path: string; title: string; description: string; icon?: unknown }> = []
  const walk = (nodes: MenuNode[]) => {
    for (const node of nodes) {
      const page = PAGE_REGISTRY[node.code]
      if (page && page.description) {
        links.push({
          path: page.path,
          title: node.name || page.title,
          description: page.description,
          icon: page.icon,
        })
      }
      if (node.children?.length) walk(node.children)
    }
  }
  walk(auth.menus)
  return links
})
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never">
      <div class="flex items-center gap-4">
        <el-avatar :size="56" class="shrink-0 bg-[var(--el-color-primary)] text-xl">
          {{ avatarText }}
        </el-avatar>
        <div class="min-w-0">
          <p class="truncate text-lg font-semibold">
            你好，{{ auth.displayName }}
            <el-tag v-if="auth.root" type="warning" effect="plain" size="small" class="ml-1 align-middle">
              超级管理员
            </el-tag>
          </p>
          <p class="mt-1 text-sm text-[var(--el-text-color-secondary)]">
            账号 {{ auth.user?.username }}
            <template v-if="auth.roles.length">
              · 角色：{{ auth.roles.map((role) => role.name).join('、') }}
            </template>
          </p>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" header="快捷入口">
      <div v-if="quickLinks.length" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <RouterLink
          v-for="link in quickLinks"
          :key="link.path"
          :to="link.path"
          class="group flex items-start gap-3 rounded-lg border border-[var(--el-border-color-lighter)] p-4 transition-colors hover:border-[var(--el-color-primary-light-5)] hover:bg-[var(--el-fill-color-light)]"
        >
          <el-icon
            v-if="link.icon"
            :size="22"
            class="mt-0.5 shrink-0 text-[var(--el-text-color-secondary)] group-hover:text-[var(--el-color-primary)]"
          >
            <component :is="link.icon" />
          </el-icon>
          <div class="min-w-0">
            <p class="font-medium text-[var(--el-text-color-primary)]">{{ link.title }}</p>
            <p class="mt-0.5 truncate text-xs text-[var(--el-text-color-secondary)]">
              {{ link.description }}
            </p>
          </div>
        </RouterLink>
      </div>
      <el-empty v-else description="当前账号暂无可访问的功能页" :image-size="80" />
    </el-card>
  </div>
</template>
