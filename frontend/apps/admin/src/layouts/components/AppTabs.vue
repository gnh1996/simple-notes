<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useTabsStore } from '@/stores/tabs'

type TabCommand = 'current' | 'others' | 'right' | 'all'

const route = useRoute()
const router = useRouter()
const tabsStore = useTabsStore()

const active = computed({
  get: () => route.path,
  set: (path: string) => {
    if (path !== route.path) void router.push(path)
  },
})

const closableCount = computed(() => tabsStore.tabs.filter((tab) => !tab.affix).length)

function isAffix(path: string): boolean {
  return Boolean(tabsStore.tabs.find((tab) => tab.path === path)?.affix)
}

function hasRight(path: string): boolean {
  const index = tabsStore.tabs.findIndex((tab) => tab.path === path)
  return index !== -1 && tabsStore.tabs.slice(index + 1).some((tab) => !tab.affix)
}

function canCloseOthers(path: string): boolean {
  // 右键固定页签时，其他全部可关闭；否则至少要还有另一个可关闭页签
  return isAffix(path) ? closableCount.value > 0 : closableCount.value > 1
}

/**
 * 关闭动作统一入口：右键目标 / 工具栏均走这里。
 * 右键作用于被右键的页签，而不是当前路由；只有当前页签被关掉时才跳转。
 */
async function runClose(command: TabCommand, path: string): Promise<void> {
  const result =
    command === 'all'
      ? await tabsStore.closeAll()
      : command === 'others'
        ? await tabsStore.closeOthers(path)
        : command === 'right'
          ? await tabsStore.closeRight(path)
          : await tabsStore.close(path)
  if (!result.closed) return
  if (tabsStore.tabs.some((tab) => tab.path === route.path)) return

  const next =
    result.next && tabsStore.tabs.some((tab) => tab.path === result.next)
      ? result.next
      : (tabsStore.tabs[tabsStore.tabs.length - 1]?.path ?? '/dashboard')
  if (next !== route.path) await router.push(next)
}

function onCommand(command: string, path: string): void {
  void runClose(command as TabCommand, path)
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
      @tab-remove="(name: string | number) => onCommand('current', String(name))"
    >
      <el-tab-pane
        v-for="tab in tabsStore.tabs"
        :key="tab.path"
        :name="tab.path"
        :closable="!tab.affix"
      >
        <template #label>
          <el-dropdown trigger="contextmenu" @command="onCommand($event, tab.path)">
            <span class="block max-w-40 truncate text-sm" :title="tab.title" @contextmenu.prevent>
              {{ tab.title }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="current" :disabled="isAffix(tab.path)">
                  关闭当前
                </el-dropdown-item>
                <el-dropdown-item command="others" :disabled="!canCloseOthers(tab.path)">
                  关闭其他
                </el-dropdown-item>
                <el-dropdown-item command="right" :disabled="!hasRight(tab.path)">
                  关闭右侧
                </el-dropdown-item>
                <el-dropdown-item command="all" divided :disabled="closableCount === 0">
                  关闭全部
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-dropdown trigger="click" @command="onCommand($event, route.path)">
      <el-button link size="small" class="shrink-0" title="页签操作">
        <el-icon><ArrowDown /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="current" :disabled="isAffix(route.path)">
            关闭当前
          </el-dropdown-item>
          <el-dropdown-item command="others" :disabled="!canCloseOthers(route.path)">
            关闭其他
          </el-dropdown-item>
          <el-dropdown-item command="right" :disabled="!hasRight(route.path)">
            关闭右侧
          </el-dropdown-item>
          <el-dropdown-item command="all" divided :disabled="closableCount === 0">
            关闭全部
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>
