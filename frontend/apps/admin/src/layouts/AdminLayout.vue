<script setup lang="ts">
import { useBreakpoint } from '@simple-notes/shared'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { useTabsStore } from '@/stores/tabs'

import AppHeader from './components/AppHeader.vue'
import AppSidebar from './components/AppSidebar.vue'
import AppTabs from './components/AppTabs.vue'

const route = useRoute()
const tabsStore = useTabsStore()

// 全项目统一断点来源（@simple-notes/shared）：移动端用抽屉承载侧边栏
const { isMobile } = useBreakpoint()

const collapsed = ref(false)
const drawerVisible = ref(false)

watch(
  () => route.fullPath,
  () => {
    if (route.meta.title) {
      tabsStore.open({
        path: route.path,
        title: route.meta.title,
        affix: route.meta.affix,
      })
    }
    drawerVisible.value = false
  },
  { immediate: true },
)

const asideWidth = computed(() => (collapsed.value ? '64px' : '224px'))
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-[var(--el-bg-color-page)]">
    <aside
      v-if="!isMobile"
      class="shrink-0 border-r border-[var(--el-border-color-lighter)] transition-[width] duration-200"
      :style="{ width: asideWidth }"
    >
      <AppSidebar :collapsed="collapsed" />
    </aside>

    <el-drawer
      v-else
      v-model="drawerVisible"
      direction="ltr"
      size="232px"
      :with-header="false"
      class="app-menu-drawer"
    >
      <AppSidebar :collapsed="false" />
    </el-drawer>

    <div class="flex min-w-0 flex-1 flex-col">
      <AppHeader
        :collapsed="collapsed"
        @toggle-collapse="collapsed = !collapsed"
        @open-menu="drawerVisible = true"
      />
      <AppTabs />
      <main class="min-h-0 flex-1 overflow-y-auto p-4">
        <RouterView v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>
      </main>
    </div>
  </div>
</template>
