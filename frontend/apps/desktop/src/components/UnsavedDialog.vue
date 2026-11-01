<script setup lang="ts">
import type { DocumentTab } from '../types/document'

defineProps<{
  open: boolean
  mode: 'tab' | 'app' | 'others' | 'right' | 'all'
  files: DocumentTab[]
}>()

const emit = defineEmits<{
  'save-all': []
  'discard-all': []
  cancel: []
  'one-by-one': []
}>()

function title(mode: 'tab' | 'app' | 'others' | 'right' | 'all'): string {
  switch (mode) {
    case 'tab':
      return '关闭这个标签？'
    case 'app':
      return '退出 Simple Notes？'
    case 'others':
      return '关闭其他标签？'
    case 'right':
      return '关闭右侧标签？'
    default:
      return '关闭全部标签？'
  }
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-6">
    <div class="w-full max-w-md rounded-xl border border-app-border bg-app-panel p-4 shadow-xl">
      <h3 class="text-sm font-medium">{{ title(mode) }}</h3>
      <p class="mt-1 text-[11px] leading-relaxed text-app-muted">
        有 {{ files.length }} 个文件还没有保存。保存会写回源文件；放弃会丢失这些修改。
      </p>
      <ul class="mt-3 max-h-40 overflow-y-auto rounded border border-app-border bg-app-bg px-2 py-1 text-[11px]">
        <li v-for="tab in files" :key="tab.key" class="truncate py-0.5" :title="tab.file?.path">
          {{ tab.file?.name || tab.title }}
        </li>
      </ul>
      <div class="mt-5 flex flex-wrap justify-end gap-2">
        <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg" type="button" @click="emit('cancel')">
          取消
        </button>
        <button
          v-if="files.length > 1"
          class="rounded border border-app-border px-3 py-1.5 text-xs text-app-muted hover:bg-app-bg"
          type="button"
          @click="emit('one-by-one')"
        >
          逐个处理
        </button>
        <button
          class="rounded border border-app-border px-3 py-1.5 text-xs text-red-400 hover:bg-app-bg"
          type="button"
          @click="emit('discard-all')"
        >
          {{ files.length > 1 ? '全部放弃' : '放弃' }}
        </button>
        <button
          class="rounded bg-brand-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-400"
          type="button"
          @click="emit('save-all')"
        >
          {{ files.length > 1 ? '全部保存' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>
