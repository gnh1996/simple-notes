<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { FolderRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import type { LibraryFilter, LibraryItem } from '../types/library'
import { ref } from 'vue'

const emit = defineEmits<{
  'update:sourceFilter': [value: LibraryFilter]
  'update:kind': [value: 'NOTE' | 'POST']
  'update:folderId': [value: string]
  'update:keyword': [value: string]
  select: [item: LibraryItem]
  'new-note': []
  'open-file': []
  'new-folder': [name: string]
  'rename-folder': [folder: FolderRow, name: string]
  'delete-folder': [folder: FolderRow]
  'remove-recent': [path: string]
  'clear-recent': []
  retry: []
  login: []
  close: []
  contextmenu: [item: LibraryItem, event: MouseEvent]
}>()

const props = defineProps<{
  session: Session | null
  folders: FolderRow[]
  items: LibraryItem[]
  activeKey: string
  sourceFilter: LibraryFilter
  kind: 'NOTE' | 'POST'
  folderId: string
  keyword: string
  online: boolean
  pending: number
}>()

const creatingFolder = ref(false)
const newFolderName = ref('')
const renamingId = ref('__none__')
const renameValue = ref('')

// 本地自建文件夹始终可管理（不需要登录）；服务端文件夹仅在登录后可管理。
function canManageFolder(folder: FolderRow): boolean {
  if (!folder.id || folder.id === 'local-files' || folder.id === 'uncategorized') {
    return false
  }
  return folder.local || Boolean(props.session)
}

function canCreateFolder(): boolean {
  return props.sourceFilter === 'local' || (Boolean(props.session) && props.sourceFilter === 'server')
}

function submitNewFolder(): void {
  const name = newFolderName.value.trim()
  if (!name) {
    creatingFolder.value = false
    return
  }
  emit('new-folder', name)
  creatingFolder.value = false
  newFolderName.value = ''
}

function startRename(folder: FolderRow): void {
  renamingId.value = folder.id
  renameValue.value = folder.name
}

function submitRename(folder: FolderRow): void {
  if (renamingId.value !== folder.id) {
    return
  }
  const name = renameValue.value.trim()
  renamingId.value = '__none__'
  if (name && name !== folder.name) {
    emit('rename-folder', folder, name)
  }
}

function sourceClass(source: string): string {
  if (source === 'file') {
    return 'text-amber-400'
  }
  if (source === 'local') {
    return 'text-violet-300'
  }
  return 'text-app-muted'
}

function sourceLabel(source: string): string {
  if (source === 'file') {
    return '文件'
  }
  if (source === 'local') {
    return '本地'
  }
  return '云端'
}

function formatTime(value: number): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`
}
</script>

<template>
  <aside class="flex h-full min-h-0 flex-col border-r border-app-border bg-app-panel/60" aria-label="资料库">
    <div class="flex h-9 shrink-0 items-center gap-1 border-b border-app-border px-2">
      <span class="text-[11px] font-medium uppercase tracking-wide text-app-muted">资料库</span>
      <button
        class="ml-auto flex h-6 w-6 items-center justify-center rounded text-app-muted hover:bg-app-bg hover:text-app-fg"
        type="button"
        title="收起资料库（Ctrl+B）"
        @click="emit('close')"
      >
        ‹
      </button>
    </div>

    <div class="flex shrink-0 items-center gap-0.5 border-b border-app-border px-1.5 py-1 text-[11px]">
      <button
        v-for="option in [
          { id: 'file', label: '最近文件' },
          { id: 'local', label: '本地' },
          ...(session ? [{ id: 'server', label: '云端' }] : []),
        ]"
        :key="option.id"
        class="rounded px-1.5 py-1"
        :class="sourceFilter === option.id ? 'bg-brand-500/10 text-brand-400' : 'text-app-muted hover:bg-app-bg'"
        type="button"
        @click="emit('update:sourceFilter', option.id as LibraryFilter)"
      >
        {{ option.label }}
      </button>
    </div>

    <div
      v-if="session && sourceFilter === 'server'"
      class="flex shrink-0 items-center gap-2 border-b border-app-border px-2 py-1.5 text-[11px]"
    >
      <div class="flex items-center gap-0.5 rounded border border-app-border/60 bg-app-bg p-0.5">
        <button
          class="rounded px-2 py-1"
          :class="kind === 'NOTE' ? 'bg-brand-500/10 text-brand-400' : 'text-app-muted hover:text-app-fg'"
          type="button"
          @click="emit('update:kind', 'NOTE')"
        >
          随手记
        </button>
        <button
          class="rounded px-2 py-1"
          :class="kind === 'POST' ? 'bg-brand-500/10 text-brand-400' : 'text-app-muted hover:text-app-fg'"
          type="button"
          @click="emit('update:kind', 'POST')"
        >
          文章
        </button>
      </div>
      <span v-if="kind === 'POST'" class="ml-auto text-[10px] text-app-muted">文章没有文件夹</span>
    </div>

    <div class="flex min-h-0 flex-1">
      <div
        v-if="sourceFilter !== 'file' && kind !== 'POST'"
        class="flex w-[160px] shrink-0 flex-col overflow-y-auto border-r border-app-border px-1.5 py-2"
      >
        <div
          v-for="folder in folders"
          :key="folder.id || 'all'"
          class="group flex items-center gap-1 rounded"
          :class="folderId === folder.id ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
        >
          <button
            v-if="renamingId !== folder.id"
            class="min-w-0 flex-1 truncate rounded px-1.5 py-1.5 text-left text-xs"
            :title="folder.name"
            type="button"
            @click="emit('update:folderId', folder.id)"
          >
            {{ folder.name }}
          </button>
          <input
            v-else
            v-model="renameValue"
            class="min-w-0 flex-1 rounded border border-brand-400/60 bg-app-bg px-1 py-1 text-[11px] outline-none"
            @keydown.enter="submitRename(folder)"
            @keydown.esc="renamingId = '__none__'"
            @blur="submitRename(folder)"
          />
          <span v-if="renamingId !== folder.id && folder.noteCount >= 0" class="shrink-0 text-[10px] text-app-muted">
            {{ folder.noteCount }}
          </span>
          <button
            v-if="canManageFolder(folder) && renamingId !== folder.id"
            class="hidden px-0.5 text-[10px] text-app-muted group-hover:block hover:text-brand-400"
            type="button"
            title="重命名"
            @click="startRename(folder)"
          >
            改
          </button>
          <button
            v-if="canManageFolder(folder)"
            class="hidden px-0.5 text-[10px] text-red-400 group-hover:block"
            type="button"
            title="删除"
            @click="emit('delete-folder', folder)"
          >
            删
          </button>
        </div>

        <button
          v-if="canCreateFolder()"
          class="mt-1 rounded px-1.5 py-1 text-left text-[11px] text-app-muted hover:bg-app-bg hover:text-brand-400 disabled:opacity-40"
          :disabled="sourceFilter === 'server' && !online"
          :title="
            sourceFilter === 'server' && !online
              ? '离线时不能新建云端文件夹'
              : sourceFilter === 'local'
                ? '新建本地文件夹（仅本机，不同步）'
                : '新建云端文件夹'
          "
          type="button"
          @click="creatingFolder = true"
        >
          + 新建文件夹
        </button>
        <input
          v-if="creatingFolder"
          v-model="newFolderName"
          class="mt-1 w-full rounded border border-brand-400/60 bg-app-bg px-1.5 py-1 text-[11px] outline-none"
          placeholder="名称，回车确认"
          @keydown.enter="submitNewFolder"
          @keydown.esc="creatingFolder = false"
        />
      </div>

      <div class="flex min-w-0 flex-1 flex-col">
        <div class="flex shrink-0 items-center gap-1 border-b border-app-border px-2 py-1.5">
          <input
            :value="keyword"
            class="min-w-0 flex-1 rounded border border-app-border bg-app-bg px-2 py-1 text-[11px] outline-none focus:border-brand-400"
            :placeholder="sourceFilter === 'file' ? '筛选最近文件…' : '筛选当前列表…'"
            spellcheck="false"
            @input="emit('update:keyword', ($event.target as HTMLInputElement).value)"
          />
          <button
            v-if="sourceFilter !== 'file'"
            class="flex h-6 w-6 shrink-0 items-center justify-center rounded border border-app-border text-sm leading-none text-app-muted hover:bg-app-bg hover:text-brand-400"
            type="button"
            title="新建本地笔记（Ctrl+N）"
            @click="emit('new-note')"
          >
            +
          </button>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto">
          <div
            v-for="item in items"
            :key="item.key"
            class="group block w-full cursor-pointer border-b border-app-border/60 px-2.5 py-2 text-left last:border-b-0"
            :class="activeKey === item.key ? 'bg-brand-500/10' : 'hover:bg-app-bg'"
            role="button"
            tabindex="0"
            @click="emit('select', item)"
            @keydown.enter="emit('select', item)"
            @contextmenu.prevent="emit('contextmenu', item, $event)"
          >
            <div class="flex items-center gap-1.5">
              <span v-if="item.pinned" class="shrink-0 text-[10px] text-brand-400">置顶</span>
              <span class="min-w-0 flex-1 truncate text-xs" :class="item.exists === false ? 'text-red-400' : ''">{{ item.title }}</span>
              <span class="shrink-0 text-[10px]" :class="sourceClass(item.source)">{{ sourceLabel(item.source) }}</span>
              <span
                v-if="item.conflict"
                class="shrink-0 rounded bg-red-500/10 px-1 text-[10px] text-red-400"
              >
                冲突
              </span>
              <span
                v-else-if="item.dirty"
                class="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-400"
                title="未同步 / 未保存"
              />
            </div>
            <div class="mt-0.5 flex items-center gap-1.5 text-[10px] text-app-muted">
              <span class="min-w-0 flex-1 truncate" :title="item.subtitle">{{ item.subtitle }}</span>
              <span class="shrink-0">{{ formatTime(item.updatedAt) }}</span>
              <button
                v-if="item.source === 'file'"
                class="hidden shrink-0 px-0.5 text-app-muted hover:text-red-400 group-hover:block"
                type="button"
                title="从最近列表移除"
                @click.stop="emit('remove-recent', item.recent?.path ?? '')"
              >
                ×
              </button>
            </div>
          </div>
          <div v-if="!items.length" class="p-3 text-[11px] leading-relaxed text-app-muted">
            <p>
              {{ sourceFilter === 'file'
                ? '还没有最近打开的文件。'
                : sourceFilter === 'local'
                  ? '暂无本地笔记；新建或打开本地文件。'
                  : kind === 'POST'
                    ? '暂无文章；同步索引或新建一篇。'
                    : '暂无云端笔记；同步索引或新建一篇。' }}
            </p>
            <button
              v-if="sourceFilter === 'file'"
              class="mt-2 rounded border border-app-border px-2 py-1 text-[11px] text-brand-400 hover:bg-app-bg"
              type="button"
              @click="emit('open-file')"
            >
              打开 Markdown 文件…
            </button>
          </div>
        </div>

        <div class="flex shrink-0 items-center gap-2 border-t border-app-border px-2 py-1.5 text-[10px] text-app-muted">
          <button
            v-if="sourceFilter === 'file' && items.length"
            class="text-app-muted hover:text-red-400"
            type="button"
            @click="emit('clear-recent')"
          >
            清空最近记录
          </button>
          <button
            v-if="session && pending"
            class="text-amber-400 hover:text-amber-300"
            type="button"
            @click="emit('retry')"
          >
            待同步 {{ pending }} 项 · 重试
          </button>
          <button v-else-if="!session" class="text-brand-400 hover:text-brand-300" type="button" @click="emit('login')">
            本地模式 · 登录以同步
          </button>
          <span v-else>已连接 · 所有更改自动保存在本地</span>
        </div>
      </div>
    </div>
  </aside>
</template>
