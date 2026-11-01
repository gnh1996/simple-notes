<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { LibraryItem } from '../types/library'

defineProps<{
  items: LibraryItem[]
  session: Session | null
  loading: boolean
}>()

const emit = defineEmits<{
  'new-local': []
  'open-file': []
  'open-item': [item: LibraryItem]
  login: []
  'expand-library': []
}>()

function sourceLabel(item: LibraryItem): string {
  if (item.source === 'file') {
    return '文件'
  }
  if (item.source === 'local') {
    return '本地'
  }
  return '服务端'
}

function sourceClass(item: LibraryItem): string {
  if (item.source === 'file') {
    return 'text-amber-400'
  }
  if (item.source === 'local') {
    return 'text-violet-300'
  }
  return 'text-app-muted'
}

function formatTime(value: number): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`
}
</script>

<template>
  <div class="min-h-0 flex-1 overflow-y-auto">
    <div class="mx-auto flex w-full max-w-2xl flex-col px-8 py-16">
      <p class="text-2xl font-semibold tracking-tight">写点什么，先存在本地。</p>
      <p class="mt-2 text-sm leading-relaxed text-app-muted">
        文件、本地笔记与服务端笔记都在标签页里打开；不登录也能完整写作。
      </p>

      <div class="mt-8 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-brand-500 px-4 py-2 text-sm font-medium text-white hover:bg-brand-400"
          type="button"
          @click="emit('new-local')"
        >
          + 新建本地笔记
        </button>
        <button
          class="rounded-md border border-app-border px-4 py-2 text-sm hover:bg-app-panel"
          type="button"
          @click="emit('open-file')"
        >
          打开 Markdown 文件…
        </button>
        <button
          class="rounded-md border border-app-border px-4 py-2 text-sm text-app-muted hover:bg-app-panel"
          type="button"
          @click="emit('expand-library')"
        >
          资料库
        </button>
      </div>

      <div class="mt-12">
        <div class="flex items-center gap-2">
          <p class="text-[11px] uppercase tracking-wide text-app-muted">最近</p>
          <button
            v-if="items.length"
            class="ml-auto text-[11px] text-app-muted hover:text-brand-400"
            type="button"
            @click="emit('expand-library')"
          >
            查看全部
          </button>
        </div>
        <div v-if="items.length" class="mt-2 overflow-hidden rounded-lg border border-app-border bg-app-panel">
          <button
            v-for="item in items.slice(0, 8)"
            :key="item.key"
            class="flex w-full items-center gap-3 border-b border-app-border/60 px-3 py-2.5 text-left last:border-b-0 hover:bg-app-bg"
            type="button"
            @click="emit('open-item', item)"
          >
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{{ item.title }}</span>
              <span class="mt-0.5 block truncate text-[11px] text-app-muted" :title="item.subtitle">
                {{ item.subtitle }}
              </span>
            </span>
            <span class="shrink-0 text-[11px]" :class="sourceClass(item)">{{ sourceLabel(item) }}</span>
            <span class="shrink-0 text-[11px] text-app-muted">{{ formatTime(item.updatedAt) }}</span>
          </button>
        </div>
        <p v-else-if="!loading" class="mt-2 rounded-lg border border-dashed border-app-border px-3 py-6 text-center text-xs text-app-muted">
          还没有最近文档；新建一篇或打开一个 Markdown 文件。
        </p>
      </div>

      <div v-if="!session" class="mt-10 flex items-center gap-3 border-t border-app-border pt-4 text-[11px] text-app-muted">
        <span>登录后可把笔记同步到服务器；本地写作不受影响。</span>
        <button class="ml-auto shrink-0 text-brand-400 hover:text-brand-300" type="button" @click="emit('login')">
          登录
        </button>
      </div>
    </div>
  </div>
</template>
