<script setup lang="ts">
import type { ContextMenuItem } from '../types/context-menu'
import type { DocumentTab, TabSource } from '../types/document'
import { computed, ref, watch } from 'vue'

import ContextMenu from './ContextMenu.vue'

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
  event.preventDefault()
  menu.value = { x: event.clientX, y: event.clientY, tab }
}

const menuItems: ContextMenuItem[] = [
  { key: 'close', label: '关闭' },
  { key: 'close-others', label: '关闭其他' },
  { key: 'close-right', label: '关闭右侧' },
  { key: 'separator-1', separator: true },
  { key: 'close-all', label: '关闭全部', danger: true },
]

function runMenu(key: string): void {
  const tab = menu.value?.tab
  menu.value = null
  if (!tab) {
    return
  }
  switch (key) {
    case 'close':
      emit('close', tab)
      break
    case 'close-others':
      emit('close-others', tab)
      break
    case 'close-right':
      emit('close-right', tab)
      break
    case 'close-all':
      emit('close-all')
      break
  }
}

/**
 * 来源用「形状 + 颜色」双重编码，避免只靠颜色区分（文件琥珀 / 本地紫 / 云端中性）。
 * 路径均取自 lucide（ISC），线宽与尺寸在模板里统一。
 */
const SOURCES: Record<TabSource, { label: string; title: string; icon: string; tone: string; bg: string }> = {
  file: {
    label: '文件',
    title: '本地 Markdown 文件（显式保存到磁盘）',
    icon: 'M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7zM14 2v6h6',
    tone: 'text-amber-500',
    bg: 'bg-amber-500/10',
  },
  local: {
    label: '本地',
    title: '本地笔记（存在本机数据库）',
    icon: 'M12 3c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3zM3 6v12c0 1.66 4.03 3 9 3s9-1.34 9-3V6M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3',
    tone: 'text-violet-500',
    bg: 'bg-violet-500/10',
  },
  server: {
    label: '云端',
    title: '云端笔记（本地优先，后台同步）',
    icon: 'M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z',
    tone: 'text-app-muted',
    bg: 'bg-app-fg/5',
  },
}

type IndicatorKind = 'conflict' | 'alert' | 'warn' | 'spin' | 'dot' | 'hollow' | 'check'

interface TabState {
  kind: IndicatorKind
  title: string
}

function savedLabel(tab: DocumentTab): string {
  if (tab.source === 'file') {
    return '已保存到文件'
  }
  return tab.source === 'local' ? '已保存到本地' : '已同步'
}

function unsavedLabel(tab: DocumentTab): string {
  if (tab.source === 'file') {
    return '未保存到文件'
  }
  return tab.source === 'local' ? '未保存到本地' : '有未推送修改'
}

/**
 * 右侧状态：实心点 = 有未保存内容，绿勾 = 已保存，转圈 = 保存中，
 * 红色 = 冲突 / 失败，空心圈 = 尚未加载（懒加载的文件标签）。
 * 形状本身可读，不依赖颜色。
 */
function stateOf(tab: DocumentTab): TabState {
  if (tab.error) {
    return { kind: 'alert', title: `标签加载失败${tab.error ? `：${tab.error}` : ''}` }
  }
  switch (tab.saveState) {
    case 'conflict':
      return { kind: 'conflict', title: '与云端冲突，在文档头处理' }
    case 'missing':
      return { kind: 'alert', title: '源文件已被删除' }
    case 'error':
      return { kind: 'alert', title: '保存失败' }
    case 'saving':
      return { kind: 'spin', title: '正在保存…' }
    case 'offline':
      return tab.source === 'local'
        ? { kind: 'check', title: '已保存到本地' }
        : { kind: 'dot', title: '离线待同步' }
    case 'modified':
      return { kind: 'warn', title: '文件已被其他程序修改' }
  }
  if (tab.dirty) {
    return { kind: 'dot', title: unsavedLabel(tab) }
  }
  if (tab.loading) {
    return { kind: 'spin', title: '正在载入…' }
  }
  if (!tab.loaded) {
    return { kind: 'hollow', title: '尚未载入，激活后读取' }
  }
  return { kind: 'check', title: savedLabel(tab) }
}

function titleOf(tab: DocumentTab): string {
  if (tab.title?.trim()) {
    return tab.title
  }
  return tab.source === 'file' ? tab.file?.name || '未命名' : '未命名'
}

interface TabRow {
  tab: DocumentTab
  title: string
  source: (typeof SOURCES)[TabSource]
  state: TabState
  tooltip: string
}

const rows = computed<TabRow[]>(() =>
  props.tabs.map((tab) => {
    const source = SOURCES[tab.source]
    const state = stateOf(tab)
    const tooltip = tab.source === 'file' ? tab.file?.path || titleOf(tab) : `${source.label}笔记 · ${titleOf(tab)}`
    return { tab, title: titleOf(tab), source, state, tooltip: `${tooltip} · ${state.title}` }
  }),
)

function onWheel(event: WheelEvent): void {
  const element = event.currentTarget as HTMLElement
  element.scrollLeft += event.deltaY
}
</script>

<template>
  <div class="flex h-9 shrink-0 items-end gap-1 border-b border-app-border bg-app-bg px-1.5">
    <div ref="strip" class="tab-scroll flex min-w-0 flex-1 items-end gap-1 overflow-x-auto" @wheel.prevent="onWheel">
      <div
        v-for="row in rows"
        :key="row.tab.key"
        :data-tab-key="row.tab.key"
        class="group flex h-8 min-w-[104px] max-w-[200px] flex-[1_1_150px] cursor-pointer items-center gap-1 rounded-t border border-b-0 px-1.5 text-xs"
        :class="
          row.tab.key === activeKey
            ? 'relative border-app-border bg-app-panel text-app-fg after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:rounded-t after:bg-brand-500'
            : 'border-transparent text-app-muted hover:bg-app-panel/60 hover:text-app-fg'
        "
        :title="row.tooltip"
        @click="emit('select', row.tab.key)"
        @auxclick.middle.prevent="emit('close', row.tab)"
        @contextmenu.prevent="openMenu(row.tab, $event)"
      >
        <!-- 来源：形状 + 颜色双重编码（文件 / 本地 / 云端） -->
        <span
          class="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded"
          :class="[row.source.bg, row.source.tone]"
          :title="row.source.title"
        >
          <svg
            class="h-2.5 w-2.5"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path :d="row.source.icon" />
          </svg>
        </span>

        <span class="min-w-0 flex-1 truncate">{{ row.title }}</span>

        <!-- 保存状态：实心点=未保存 绿勾=已保存 转圈=保存中 红色=冲突/失败 空心=未载入 -->
        <span
          class="flex h-3.5 w-3.5 shrink-0 items-center justify-center"
          role="img"
          :title="row.state.title"
          :aria-label="row.state.title"
        >
          <span
            v-if="row.state.kind === 'conflict'"
            class="h-2 w-2 rounded-full bg-red-500 ring-2 ring-red-500/25"
          />
          <span
            v-else-if="row.state.kind === 'alert'"
            class="flex h-3.5 w-3.5 items-center justify-center rounded-full bg-red-500/15 text-[9px] font-bold leading-none text-red-400"
            >!</span
          >
          <span v-else-if="row.state.kind === 'warn'" class="text-[10px] font-bold leading-none text-amber-400">!</span>
          <svg
            v-else-if="row.state.kind === 'spin'"
            class="h-3 w-3 animate-spin text-brand-400"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <circle
              cx="12"
              cy="12"
              r="9"
              stroke="currentColor"
              stroke-width="3"
              stroke-linecap="round"
              stroke-dasharray="42"
              stroke-dashoffset="28"
            />
          </svg>
          <span v-else-if="row.state.kind === 'dot'" class="h-2 w-2 rounded-full bg-amber-400" />
          <span v-else-if="row.state.kind === 'hollow'" class="h-2 w-2 rounded-full border border-app-muted/50" />
          <svg
            v-else-if="row.state.kind === 'check'"
            class="h-3 w-3 transition-colors"
            :class="row.tab.key === activeKey ? 'text-emerald-500' : 'text-emerald-500/60 group-hover:text-emerald-500'"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="3"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M20 6 9 17l-5-5" />
          </svg>
        </span>

        <button
          class="flex h-4 w-4 shrink-0 items-center justify-center rounded text-app-muted opacity-0 hover:bg-app-bg hover:text-red-400 group-hover:opacity-100"
          type="button"
          :aria-label="`关闭 ${row.title}`"
          title="关闭标签（Ctrl+W）"
          @click.stop="emit('close', row.tab)"
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

    <ContextMenu
      :open="!!menu"
      :x="menu?.x ?? 0"
      :y="menu?.y ?? 0"
      :items="menuItems"
      :min-width="176"
      @select="runMenu"
      @close="menu = null"
    />
  </div>
</template>
