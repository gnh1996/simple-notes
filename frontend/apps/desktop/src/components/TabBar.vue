<script setup lang="ts">
import type { DocumentTab } from '../types/document'
import { ref, watch } from 'vue'

const props = defineProps<{
  tabs: DocumentTab[]
  activeKey: string
}>()

const emit = defineEmits<{
  select: [key: string]
  close: [tab: DocumentTab]
  'new-tab': []
  'close-others': [tab: DocumentTab]
  'close-right': [tab: DocumentTab]
  'close-all': []
}>()

const menu = ref<{ x: number; y: number; tab: DocumentTab } | null>(null)
const strip = ref<HTMLElement>()

watch(
  () => props.activeKey,
  () => {
    menu.value = null
    requestAnimationFrame(() => {
      const target = [...(strip.value?.querySelectorAll<HTMLElement>('[data-tab-key]') ?? [])].find(
        (element) => element.dataset.tabKey === props.activeKey,
      )
      target?.scrollIntoView({ inline: 'nearest', block: 'nearest' })
    })
  },
)

function openMenu(tab: DocumentTab, event: MouseEvent): void {
  menu.value = { x: event.clientX, y: event.clientY, tab }
}

function run(action: () => void): void {
  menu.value = null
  action()
}

function sourceDot(tab: DocumentTab): string {
  if (tab.source === 'file') {
    return 'bg-amber-400'
  }
  if (tab.source === 'local') {
    return 'bg-violet-400'
  }
  return 'bg-zinc-400'
}

function titleOf(tab: DocumentTab): string {
  if (tab.title?.trim()) {
    return tab.title
  }
  return tab.source === 'file' ? tab.file?.name || '未命名' : '未命名'
}

function onWheel(event: WheelEvent): void {
  const element = event.currentTarget as HTMLElement
  element.scrollLeft += event.deltaY
}
</script>

<template>
  <div class="flex h-9 shrink-0 items-end gap-1 border-b border-app-border bg-app-bg px-1.5">
    <div ref="strip" class="tab-scroll flex min-w-0 flex-1 items-end gap-1 overflow-x-auto" @wheel.prevent="onWheel">
      <div
        v-for="tab in tabs"
        :key="tab.key"
        :data-tab-key="tab.key"
        class="group flex h-8 min-w-[88px] max-w-[180px] flex-[1_1_140px] cursor-pointer items-center gap-1.5 rounded-t border border-b-0 px-2 text-xs"
        :class="
          tab.key === activeKey
            ? 'border-app-border bg-app-panel text-app-fg'
            : 'border-transparent text-app-muted hover:bg-app-panel/60 hover:text-app-fg'
        "
        :title="tab.source === 'file' ? tab.file?.path : titleOf(tab)"
        @click="emit('select', tab.key)"
        @auxclick.middle.prevent="emit('close', tab)"
        @contextmenu.prevent="openMenu(tab, $event)"
      >
        <span class="h-1.5 w-1.5 shrink-0 rounded-full" :class="sourceDot(tab)" />
        <span class="min-w-0 flex-1 truncate">{{ titleOf(tab) }}</span>
        <span v-if="tab.error" class="shrink-0 text-[10px] text-red-400" title="标签加载失败">!</span>
        <span
          v-else-if="tab.saveState === 'conflict'"
          class="h-1.5 w-1.5 shrink-0 rounded-full bg-red-400"
          title="冲突"
        />
        <span
          v-else-if="tab.dirty"
          class="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-400"
          title="未保存"
        />
        <span v-else-if="tab.saveState === 'saving'" class="shrink-0 animate-spin text-[10px]" title="保存中">⟳</span>
        <button
          class="flex h-5 w-5 shrink-0 items-center justify-center rounded text-app-muted opacity-0 hover:bg-app-bg hover:text-red-400 group-hover:opacity-100"
          type="button"
          title="关闭标签（Ctrl+W）"
          @click.stop="emit('close', tab)"
        >
          ×
        </button>
      </div>
    </div>
    <button
      class="mb-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded text-sm text-app-muted hover:bg-app-panel hover:text-app-fg"
      type="button"
      title="新建本地笔记（Ctrl+N）"
      @click="emit('new-tab')"
    >
      +
    </button>

    <div v-if="menu" class="fixed inset-0 z-40" @click="menu = null" @contextmenu.prevent="menu = null">
      <div
        class="absolute w-44 rounded-lg border border-app-border bg-app-panel p-1 shadow-xl"
        :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
        @click.stop
      >
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('close', menu!.tab))">
          关闭
        </button>
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('close-others', menu!.tab))">
          关闭其他
        </button>
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('close-right', menu!.tab))">
          关闭右侧
        </button>
        <button class="w-full rounded px-2 py-1.5 text-left text-xs text-red-400 hover:bg-app-bg" type="button" @click="run(() => emit('close-all'))">
          关闭全部
        </button>
      </div>
    </div>
  </div>
</template>
