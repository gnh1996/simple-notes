<script setup lang="ts">
/**
 * 大文本编辑（主题 HTML/CSS 可达数十 KB）。
 *
 * 背景：el-input textarea 是全受控的，每键都走 v-model → 整弹窗重渲染。
 * 原生 textarea 另有一硬伤：30KB/1594 行整块内容即 ~3 万 px 图层，超出 GPU 单纹理上限
 *（本机 WebGL MAX_TEXTURE_SIZE=16384，dpr 1.25 下约 38269 物理像素），真机拖滚动条整窗冻死。
 * 这里用 CodeMirror 6（MIT）：视口虚拟化，只渲染可视区数十行，DOM/图层永远很小，
 * 滚动条仍是全文高度，Ctrl+A / 查找 / 撤销照常工作。不分页，无需记住页码。
 *
 * 回写沿用 250ms debounce：输入只走编辑器链路；外部赋值（打开版本 / AI 填入）经 watch 同步；
 * 保存前父组件调 `flush()` 落盘。接口与之前一致（v-model + flush/focus）。
 */
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { css } from '@codemirror/lang-css'
import { html } from '@codemirror/lang-html'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { Compartment, EditorState } from '@codemirror/state'
import { oneDark } from '@codemirror/theme-one-dark'
import { EditorView, highlightActiveLine, keymap, lineNumbers } from '@codemirror/view'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { useTheme } from '@/composables/useTheme'

const props = defineProps<{ modelValue: string; lang?: 'html' | 'css' }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const { isDark } = useTheme()

const wrap = ref<HTMLDivElement | null>(null)
let view: EditorView | null = null
let timer: number | null = null
let lastEmitted = props.modelValue
let observer: IntersectionObserver | null = null

const themeSlot = new Compartment()

function baseExtensions() {
  return [
    lineNumbers(),
    highlightActiveLine(),
    history(),
    keymap.of([...defaultKeymap, ...historyKeymap]),
    props.lang === 'html' ? html() : css(),
    themeSlot.of(currentTheme()),
    EditorView.updateListener.of((update) => {
      if (update.docChanged) scheduleEmit()
    }),
  ]
}

/** 亮色贴 el-textarea 观感（CSS 变量随 admin 明暗自动适配）；暗色用 oneDark 全套。 */
function currentTheme() {
  if (isDark.value) return oneDark
  return [
    EditorView.theme({
      '&': { backgroundColor: 'var(--el-fill-color-blank)' },
      '.cm-content': {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: '12px',
        lineHeight: '1.6',
      },
      '.cm-gutters': {
        backgroundColor: 'var(--el-fill-color-light)',
        color: 'var(--el-text-color-secondary)',
        borderRight: '1px solid var(--el-border-color-lighter)',
        fontSize: '12px',
      },
      '&.cm-focused': { outline: 'none', borderColor: 'var(--el-color-primary)' },
    }),
    syntaxHighlighting(defaultHighlightStyle),
  ]
}

function docText(): string {
  return view?.state.doc.toString() ?? ''
}

function scheduleEmit(immediate = false): void {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
  if (immediate) {
    commitEmit()
    return
  }
  timer = window.setTimeout(() => {
    timer = null
    commitEmit()
  }, 250)
}

function commitEmit(): void {
  const text = docText()
  if (text !== lastEmitted) {
    lastEmitted = text
    emit('update:modelValue', text)
  }
}

function flush(): void {
  scheduleEmit(true)
}

function focus(): void {
  view?.focus()
}

defineExpose({ flush, focus })

watch(
  () => props.modelValue,
  (v) => {
    if (!view || v === lastEmitted) return
    scheduleEmit(true)
    const cur = docText()
    if (cur !== v) {
      view.dispatch({ changes: { from: 0, to: cur.length, insert: v } })
      lastEmitted = v
    }
  },
)

watch(isDark, () => {
  view?.dispatch({ effects: themeSlot.reconfigure(currentTheme()) })
})

onMounted(() => {
  const state = EditorState.create({ doc: props.modelValue, extensions: baseExtensions() })
  view = new EditorView({ state, parent: wrap.value! })
  // 初始化在隐藏 tab-pane 时尺寸为 0：可见时重新度量，避免视口计算错乱。
  observer = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) view?.requestMeasure()
  })
  observer.observe(wrap.value!)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer)
  observer?.disconnect()
  view?.destroy()
  view = null
})
</script>

<template>
  <div ref="wrap" class="cm-host" />
</template>

<style scoped>
.cm-host {
  height: 400px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  overflow: hidden;
  text-align: left;
}

.cm-host :deep(.cm-editor) {
  height: 100%;
}

.cm-host :deep(.cm-scroller) {
  line-height: 1.6;
}
</style>
