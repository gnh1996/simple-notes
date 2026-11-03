<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { FolderNode } from '../types/folders'
import type { LibraryFilter, LibraryItem } from '../types/library'
import type { ComponentPublicInstance } from 'vue'
import { nextTick, ref } from 'vue'

const emit = defineEmits<{
  'update:sourceFilter': [value: LibraryFilter]
  'update:kind': [value: 'NOTE' | 'POST']
  'update:folderId': [value: string]
  'update:keyword': [value: string]
  select: [item: LibraryItem]
  'new-note': []
  'open-file': []
  'new-folder': [name: string, parentId: string]
  'toggle-folder': [id: string]
  'rename-folder': [folder: FolderNode, name: string]
  'delete-folder': [folder: FolderNode]
  'remove-recent': [path: string]
  'clear-recent': []
  retry: []
  login: []
  close: []
  contextmenu: [item: LibraryItem, event: MouseEvent]
}>()

const props = defineProps<{
  session: Session | null
  folders: FolderNode[]
  collapsedIds: string[]
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
const creatingParentId = ref('')
const newFolderName = ref('')
const newFolderInput = ref<HTMLInputElement | null>(null)
const renamingId = ref('__none__')
const renameValue = ref('')

// 本地文件夹始终可管理（不需要登录）；云端文件夹仅在登录后可管理。
function canManageFolder(folder: FolderNode): boolean {
  if (!folder.id || folder.id === 'uncategorized') {
    return false
  }
  return folder.local || Boolean(props.session)
}

function canCreateFolder(): boolean {
  return props.sourceFilter === 'local' || (Boolean(props.session) && props.sourceFilter === 'server')
}

function isCollapsed(id: string): boolean {
  return props.collapsedIds.includes(id)
}

function startCreateFolder(parentId = ''): void {
  creatingParentId.value = parentId
  creatingFolder.value = true
  newFolderName.value = ''
  void nextTick(() => newFolderInput.value?.focus())
}

function cancelCreateFolder(): void {
  creatingFolder.value = false
  creatingParentId.value = ''
  newFolderName.value = ''
}

function setNewFolderInput(el: Element | ComponentPublicInstance | null): void {
  newFolderInput.value = el instanceof HTMLInputElement ? el : null
}

function submitNewFolder(): void {
  const name = newFolderName.value.trim()
  if (!name) {
    creatingFolder.value = false
    creatingParentId.value = ''
    return
  }
  emit('new-folder', name, creatingParentId.value)
  creatingFolder.value = false
  creatingParentId.value = ''
  newFolderName.value = ''
}

function startRename(folder: FolderNode): void {
  renamingId.value = folder.id
  renameValue.value = folder.name
}

function submitRename(folder: FolderNode): void {
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
        <template v-for="folder in folders" :key="folder.id || 'all'">
          <div
            class="group flex items-center gap-1 rounded"
            :class="folderId === folder.id ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
            :style="folder.id ? { paddingLeft: `${(folder.depth - 1) * 10}px` } : undefined"
          >
            <button
              v-if="folder.hasChildren"
              class="shrink-0 rounded px-0.5 text-[10px] text-app-muted hover:text-brand-400"
              type="button"
              :title="isCollapsed(folder.id) ? '展开' : '收起'"
              @click.stop="emit('toggle-folder', folder.id)"
            >
              {{ isCollapsed(folder.id) ? '▸' : '▾' }}
            </button>
            <span v-else class="w-[11px] shrink-0" />
            <button
              v-if="renamingId !== folder.id"
              class="min-w-0 flex-1 truncate rounded px-1.5 py-1.5 text-left text-xs"
              :title="folder.path"
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
              class="shrink-0 rounded px-0.5 text-[11px] leading-none text-app-muted hover:text-brand-400"
              type="button"
              title="在此文件夹下新建子文件夹"
              @click="startCreateFolder(folder.id)"
            >
              +
            </button>
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
              class="hidden px-0.5 text-[10px] text-red-400 group-hover:block disabled:cursor-not-allowed disabled:opacity-40"
              type="button"
              :disabled="folder.hasChildren"
              :title="folder.hasChildren ? '请先删除子文件夹' : '删除'"
              @click="emit('delete-folder', folder)"
            >
              删
            </button>
          </div>

          <div
            v-if="creatingFolder && !!folder.id && creatingParentId === folder.id"
            class="mb-1 rounded bg-app-bg/60 px-1.5 py-1"
            :style="{ paddingLeft: `${(folder.depth - 1) * 10 + 18}px` }"
          >
            <div class="text-[10px] text-app-muted">在「{{ folder.path }}」下新建</div>
            <input
              :ref="setNewFolderInput"
              v-model="newFolderName"
              class="mt-1 w-full rounded border border-brand-400/60 bg-app-bg px-1.5 py-1 text-[11px] outline-none"
              placeholder="名称，回车确认"
              @keydown.enter="submitNewFolder"
              @keydown.esc="cancelCreateFolder"
              @blur="cancelCreateFolder"
            />
          </div>
        </template>

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
          @click="startCreateFolder('')"
        >
          + 新建文件夹
        </button>
        <div v-if="creatingFolder && !creatingParentId" class="mt-1 text-[10px] text-app-muted">新建根文件夹</div>
        <input
          v-if="creatingFolder && !creatingParentId"
          :ref="setNewFolderInput"
          v-model="newFolderName"
          class="mt-1 w-full rounded border border-brand-400/60 bg-app-bg px-1.5 py-1 text-[11px] outline-none"
          placeholder="名称，回车确认"
          @keydown.enter="submitNewFolder"
          @keydown.esc="cancelCreateFolder"
          @blur="cancelCreateFolder"
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
