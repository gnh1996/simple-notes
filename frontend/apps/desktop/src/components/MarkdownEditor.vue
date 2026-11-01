<script setup lang="ts">
import { createEditorOptions, sanitizeHtml } from '@simple-notes/markdown'
import { TableColumnResize } from '@simple-notes/markdown/table-resize'
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
let resize: TableColumnResize | undefined
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
    // 外部整体替换内容 = 换了一篇文档，列宽属于会话态，回到自动布局
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

// 供导出（后续 PDF / 打印）复用：当前文档手动调整过的表格列宽（百分比，按表格序号）。
// 会话态数据，只存在编辑器的渲染层，getValue() / Markdown 正文不受影响。
defineExpose({
  getTableColumnWidths: () => resize?.getWidths() ?? [],
})
</script>

<template>
  <div ref="host" class="sn-editor" />
</template>

<style scoped>
/* 容器不再裁剪（工具栏气泡 / 下拉面板要能溢出卡片），因此不能给编辑器兜底 min-height，
   否则窗口较矮时编辑器会画到卡片外面去；高度完全由父级 flex 容器决定。 */
.sn-editor {
  height: 100%;
}

.sn-editor.vditor {
  border: 0;
  border-radius: 8px;
}
</style>
