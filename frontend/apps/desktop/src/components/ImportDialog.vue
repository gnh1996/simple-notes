<script setup lang="ts">
import type { FolderRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import { ref, watch } from 'vue'

const props = defineProps<{
  open: boolean
  defaultTitle: string
  canServer: boolean
  folders: FolderRow[]
  localFolders: FolderRow[]
}>()

const emit = defineEmits<{
  confirm: [payload: { title: string; target: 'local' | 'server'; folderId: string }]
  cancel: []
}>()

const title = ref('')
const target = ref<'local' | 'server'>('local')
const folderId = ref('')
const error = ref('')

function defaultLocalFolder(): string {
  return props.localFolders.find((folder) => folder.id === 'local-files')?.id ?? props.localFolders[0]?.id ?? ''
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    title.value = props.defaultTitle
    target.value = 'local'
    folderId.value = defaultLocalFolder()
    error.value = ''
  },
)

watch(target, (value) => {
  if (!props.open) {
    return
  }
  folderId.value = value === 'local' ? defaultLocalFolder() : ''
})

watch(
  () => props.canServer,
  (canServer) => {
    if (!canServer && target.value === 'server') {
      target.value = 'local'
    }
  },
)

function submit(): void {
  const value = title.value.trim()
  if (!value) {
    error.value = '标题不能为空'
    return
  }
  if ([...value].length > 200) {
    error.value = '标题不能超过 200 个字符'
    return
  }
  emit('confirm', {
    title: value,
    target: target.value,
    folderId: folderId.value,
  })
}

function cancel(): void {
  emit('cancel')
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6">
    <div class="w-full max-w-md rounded-xl border border-app-border bg-app-panel p-4 shadow-xl">
      <h3 class="text-sm font-medium">纳入管理</h3>
      <p class="mt-1 text-[11px] leading-relaxed text-app-muted">
        会复制当前编辑器内容建立一篇笔记；源文件不会被继续跟踪。
      </p>

      <label class="mt-4 block text-xs text-app-muted">
        标题
        <input
          v-model="title"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          maxlength="200"
          placeholder="标题"
        />
      </label>

      <div class="mt-4 space-y-2">
        <label
          class="flex cursor-pointer items-start gap-2 rounded border border-app-border px-3 py-2 text-xs hover:bg-app-bg"
          :class="target === 'local' ? 'border-brand-400/60 bg-brand-500/10' : ''"
        >
          <input v-model="target" class="mt-0.5" type="radio" value="local" />
          <span>
            <span class="block text-app-fg">仅本地</span>
            <span class="mt-0.5 block text-[11px] text-app-muted">归入本地文件夹，不连接服务器</span>
          </span>
        </label>
        <label
          class="flex cursor-pointer items-start gap-2 rounded border border-app-border px-3 py-2 text-xs hover:bg-app-bg"
          :class="[
            target === 'server' ? 'border-brand-400/60 bg-brand-500/10' : '',
            canServer ? '' : 'cursor-not-allowed opacity-50',
          ]"
        >
          <input v-model="target" class="mt-0.5" type="radio" value="server" :disabled="!canServer" />
          <span>
            <span class="block text-app-fg">云端（当前账号）</span>
            <span class="mt-0.5 block text-[11px] text-app-muted">
              {{ canServer ? '创建后进入现有同步流程' : '登录后可用' }}
            </span>
          </span>
        </label>
      </div>

      <label v-if="target === 'local'" class="mt-3 block text-xs text-app-muted">
        文件夹
        <select
          v-model="folderId"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
        >
          <option v-for="folder in localFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
        </select>
      </label>

      <label v-if="target === 'server'" class="mt-3 block text-xs text-app-muted">
        文件夹
        <select
          v-model="folderId"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
        >
          <option value="">未分类</option>
          <option v-for="folder in folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
        </select>
      </label>

      <p v-if="!canServer" class="mt-3 text-[11px] text-amber-400">
        当前未登录，只能保存为「仅本地」；登录后可同步到云端。
      </p>
      <p v-if="error" class="mt-3 text-[11px] text-red-400">{{ error }}</p>

      <div class="mt-5 flex justify-end gap-2">
        <button
          class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg"
          type="button"
          @click="cancel"
        >
          取消
        </button>
        <button
          class="rounded bg-brand-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-400"
          type="button"
          @click="submit"
        >
          确定
        </button>
      </div>
    </div>
  </div>
</template>
