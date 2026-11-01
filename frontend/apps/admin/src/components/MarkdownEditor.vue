<script setup lang="ts">
import { createEditorOptions, sanitizeHtml } from '@simple-notes/markdown'
import { TableColumnResize } from '@simple-notes/markdown/table-resize'
import Vditor from 'vditor'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

export interface UploadedFile {
  url: string
  alt?: string
  name?: string
  mimeType?: string
}

const props = defineProps<{
  modelValue: string
  theme: 'light' | 'dark'
  cacheId: string
  placeholder?: string
  height?: string
  upload?: (files: File[]) => Promise<UploadedFile[]>
  onUploadError?: (error: unknown) => void
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const host = ref<HTMLDivElement>()
let editor: Vditor | undefined
let resize: TableColumnResize | undefined
let ready = false
let syncing = false
let pendingValue: string | undefined

function cdn(): string {
  return `${import.meta.env.BASE_URL}vditor`.replace(/\/$/, '')
}

function insertFiles(files: File[]): Promise<string | null> {
  if (!props.upload) {
    return Promise.resolve(null)
  }
  return props.upload(files).then(
    (items) =>
      items
        .map((item) =>
          item.mimeType?.startsWith('image/')
            ? `![${item.alt ?? ''}](${item.url})`
            : `[${item.name ?? item.alt ?? '附件'}](${item.url})`,
        )
        .join('\n') || null,
    (error) => {
      props.onUploadError?.(error)
      return null
    },
  )
}

function create(): void {
  if (!host.value) {
    return
  }
  editor = new Vditor(host.value, {
    ...createEditorOptions({
      cdn: cdn(),
      value: props.modelValue,
      theme: props.theme,
      cacheId: props.cacheId,
      placeholder: props.placeholder,
      height: props.height,
      onInput: (value) => {
        if (syncing) {
          return
        }
        emit('update:modelValue', value)
      },
      onUpload: props.upload ? insertFiles : undefined,
    }),
    after: () => {
      ready = true
      if (pendingValue !== undefined && editor && pendingValue !== editor.getValue()) {
        syncing = true
        editor.setValue(pendingValue)
        resize?.reset()
        syncing = false
      }
      pendingValue = undefined
    },
  })
}

onMounted(() => {
  create()
  if (host.value) {
    resize = new TableColumnResize(host.value)
  }
  host.value?.addEventListener('paste', onPaste, true)
})

onBeforeUnmount(() => {
  host.value?.removeEventListener('paste', onPaste, true)
  resize?.destroy()
  resize = undefined
  editor?.destroy()
  editor = undefined
})

watch(
  () => props.modelValue,
  (value) => {
    if (!ready || !editor) {
      pendingValue = value
      return
    }
    if (value === editor.getValue()) {
      return
    }
    syncing = true
    editor.setValue(value)
    // 外部整体替换内容 = 换了一篇内容，列宽属于会话态，回到自动布局
    resize?.reset()
    syncing = false
  },
)

watch(
  () => props.theme,
  (theme) => {
    editor?.setTheme(theme === 'dark' ? 'dark' : 'classic', theme, 'github')
  },
)

function onPaste(event: ClipboardEvent): void {
  const html = event.clipboardData?.getData('text/html')
  if (html) {
    event.clipboardData?.setData('text/html', sanitizeHtml(html))
  }
}

// 供导出（PDF / 打印）复用：当前内容手动调整过的表格列宽（百分比，按表格序号）。
defineExpose({
  getTableColumnWidths: () => resize?.getWidths() ?? [],
})
</script>

<template>
  <div ref="host" class="sn-markdown-editor" :style="{ height }" />
</template>

<style scoped>
.sn-markdown-editor {
  min-height: 360px;
}

.sn-markdown-editor.vditor {
  border: 0;
}
</style>
