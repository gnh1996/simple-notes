<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useTabsStore } from '@/stores/tabs'

const route = useRoute()
const router = useRouter()
const tabsStore = useTabsStore()

const active = computed({
  get: () => route.path,
  set: (path: string) => {
    if (path !== route.path) void router.push(path)
  },
})

function onRemove(name: string | number): void {
  const path = String(name)
  const next = tabsStore.close(path)
  if (path === route.path && next) {
    void router.push(next)
  }
}

function onCommand(command: string): void {
  if (command === 'others') {
    tabsStore.closeOthers(route.path)
  } else if (command === 'all') {
    tabsStore.closeAll()
    if (route.path !== '/dashboard') void router.push('/dashboard')
  }
}
</script>

<template>
  <div
    class="flex items-center border-b border-[var(--el-border-color-lighter)] bg-[var(--el-bg-color)] pl-2 pr-2"
  >
    <el-tabs
      v-model="active"
      type="card"
      class="app-tabs min-w-0 flex-1"
      @tab-remove="onRemove"
    >
      <el-tab-pane
        v-for="tab in tabsStore.tabs"
        :key="tab.path"
        :name="tab.path"
        :closable="!tab.affix"
      >
        <template #label>
          <span class="text-sm">{{ tab.title }}</span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-dropdown trigger="click" @command="onCommand">
      <el-button link size="small" class="shrink-0">
        <el-icon><ArrowDown /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="others">关闭其他页签</el-dropdown-item>
          <el-dropdown-item command="all">关闭全部页签</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
