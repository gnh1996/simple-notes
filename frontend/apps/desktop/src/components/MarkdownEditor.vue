<script setup lang="ts">
import { createEditorOptions, sanitizeHtml } from '@simple-notes/markdown'
import 'vditor/dist/index.css'
import Vditor from 'vditor'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

// 桌面端编辑器 POC：与 admin / blog 同源的 Vditor + Lute（方言 v2）。
// 资源由 `pnpm sync:vditor` 自托管到 public/vditor，禁止公共 CDN。
const props = defineProps<{
  modelValue: string
  theme: 'light' | 'dark'
  placeholder?: string
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

function create(): void {
  if (!host.value) {
    return
  }
  editor = new Vditor(host.value, {
    ...createEditorOptions({
      cdn: cdn(),
      value: props.modelValue,
      theme: props.theme,
      cacheId: 'simple-notes-desktop',
      placeholder: props.placeholder,
      height: '100%',
      onInput: (value) => {
        if (syncing) {
          return
        }
        emit('update:modelValue', value)
      },
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
  <div ref="host" class="sn-editor" />
</template>

<style scoped>
.sn-editor {
  height: 100%;
  min-height: 420px;
}

.sn-editor.vditor {
  border: 0;
  border-radius: 8px;
}
</style>
