<script setup lang="ts">
import { Binding as LogService } from '../../bindings/cc.naihao/simplenotes/desktop/internal/logging'
import { Clipboard } from '@wailsio/runtime'
import { ref } from 'vue'

const props = defineProps<{
  open: boolean
  summary: string
  detail: string
  kind: string
}>()

const emit = defineEmits<{
  close: []
}>()

const copied = ref('')

const KIND_LABELS: Record<string, string> = {
  offline: '网络',
  auth: '登录',
  server: '服务端',
  outdated: '版本',
  local: '本地',
  unknown: '未知',
}

function label(kind: string): string {
  return KIND_LABELS[kind] ?? '错误'
}

async function copyDetail(): Promise<void> {
  const text = [props.summary, '', props.detail].filter(Boolean).join('\n')
  try {
    await Clipboard.SetText(text)
    copied.value = '已复制错误详情'
  } catch {
    copied.value = '复制失败，请手动选择文本'
  }
}

async function copyDiagnostics(): Promise<void> {
  try {
    const text = await LogService.Diagnostics()
    await Clipboard.SetText(text || props.detail || props.summary)
    copied.value = '已复制诊断信息'
  } catch {
    copied.value = '复制失败，请打开日志目录'
  }
}

async function openLogs(): Promise<void> {
  try {
    await LogService.OpenLogDir()
    copied.value = '已打开日志目录'
  } catch {
    copied.value = '打开日志目录失败'
  }
}

function close(): void {
  copied.value = ''
  emit('close')
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-6" @click.self="close">
    <div class="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-xl border border-app-border bg-app-panel shadow-xl">
      <header class="flex items-start justify-between gap-4 border-b border-app-border px-4 py-3">
        <div class="min-w-0">
          <h3 class="flex items-center gap-2 text-sm font-medium">
            错误详情
            <span class="rounded bg-app-bg px-1.5 py-0.5 text-[10px] font-normal text-app-muted">{{ label(kind) }}</span>
          </h3>
          <p class="mt-1 break-words text-xs text-red-400">{{ summary }}</p>
        </div>
        <button class="shrink-0 rounded border border-app-border px-2 py-1 text-xs hover:bg-app-bg" type="button" @click="close">
          关闭
        </button>
      </header>

      <div class="min-h-0 flex-1 overflow-auto px-4 py-3">
        <p class="mb-2 text-[11px] text-app-muted">
          下方是技术细节，可用于排查问题；日志中不会记录令牌、密码或笔记正文。
        </p>
        <pre class="max-h-[46vh] overflow-auto whitespace-pre-wrap break-words rounded border border-app-border bg-app-bg p-3 text-[11px] leading-relaxed text-app-muted">{{ detail || '没有更多信息' }}</pre>
      </div>

      <footer class="flex flex-wrap items-center gap-2 border-t border-app-border px-4 py-3">
        <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg" type="button" @click="copyDetail">
          复制详情
        </button>
        <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg" type="button" @click="copyDiagnostics">
          复制诊断信息
        </button>
        <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg" type="button" @click="openLogs">
          打开日志目录
        </button>
        <span v-if="copied" class="text-[11px] text-emerald-400">{{ copied }}</span>
      </footer>
    </div>
  </div>
</template>
