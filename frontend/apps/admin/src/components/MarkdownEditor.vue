<script setup lang="ts">
import { createEditorOptions, sanitizeHtml } from '@simple-notes/markdown'
import Vditor from 'vditor'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

export interface UploadedImage {
  url: string
  alt?: string
}

const props = defineProps<{
  modelValue: string
  theme: 'light' | 'dark'
  cacheId: string
  placeholder?: string
  height?: string
  upload?: (files: File[]) => Promise<UploadedImage[]>
  onUploadError?: (error: unknown) => void
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const host = ref<HTMLDivElement>()
let editor: Vditor | undefined
let ready = false
let syncing = false
let pendingValue: string | undefined

function cdn(): string {
  return `${import.meta.env.BASE_URL}vditor`.replace(/\/$/, '')
}

function insertImages(files: File[]): Promise<string | null> {
  if (!props.upload) {
    return Promise.resolve(null)
  }
  return props.upload(files).then(
    (items) =>
      items
        .map((item) => `![${item.alt ?? ''}](${item.url})`)
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
      onUpload: props.upload ? insertImages : undefined,
    }),
    after: () => {
      ready = true
      if (pendingValue !== undefined && editor && pendingValue !== editor.getValue()) {
        syncing = true
        editor.setValue(pendingValue)
        syncing = false
      }
      pendingValue = undefined
    },
  })
}

onMounted(() => {
  create()
  host.value?.addEventListener('paste', onPaste, true)
})

onBeforeUnmount(() => {
  host.value?.removeEventListener('paste', onPaste, true)
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
