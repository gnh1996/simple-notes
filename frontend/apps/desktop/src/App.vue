<script setup lang="ts">
import type { Session } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import { Service as AuthService } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth'
import { Service as NotesService } from '../bindings/cc.naihao/simplenotes/desktop/internal/notes'
import type { RemoteSnapshot } from '../bindings/cc.naihao/simplenotes/desktop/internal/notes/models'
import { Service as FoldersService } from '../bindings/cc.naihao/simplenotes/desktop/internal/folders'
import type { FolderRow, NoteRow } from '../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import { Service as SyncService } from '../bindings/cc.naihao/simplenotes/desktop/internal/syncsvc'
import { Service as SystemService } from '../bindings/cc.naihao/simplenotes/desktop/internal/system'
import type { Status } from '../bindings/cc.naihao/simplenotes/desktop/internal/system/models'
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'

import LoginView from './components/LoginView.vue'

// 编辑器（Vditor + Lute）按需加载：列表态不解析 400KB+ 的编辑器代码，降低启动与空闲内存。
const MarkdownEditor = defineAsyncComponent(() => import('./components/MarkdownEditor.vue'))

type Kind = 'NOTE' | 'POST'

const session = ref<Session | null>(null)
const status = ref<Status | null>(null)
const kind = ref<Kind>('NOTE')
const folderId = ref('')
const keyword = ref('')
const folders = ref<FolderRow[]>([])
const notes = ref<NoteRow[]>([])
const selected = ref<NoteRow | null>(null)
const editTitle = ref('')
const editContent = ref('')
const saveState = ref<'idle' | 'saving' | 'synced' | 'conflict' | 'offline' | 'error'>('idle')
const saveMessage = ref('')
const dirty = ref(false)
const remoteTitle = ref('')
const remoteVersion = ref(0)
const syncing = ref(false)
const opening = ref(false)
const pendingOutbox = ref(0)
const creatingFolder = ref(false)
const newFolderName = ref('')
const renamingId = ref('')
const renameValue = ref('')
const remoteSnapshot = ref<RemoteSnapshot | null>(null)
const diffOpen = ref(false)
const creatingNote = ref(false)

interface DiffLine {
  type: 'same' | 'add' | 'del'
  text: string
}
const message = ref('')
const error = ref('')
const isDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)

const editorTheme = computed(() => (isDark.value ? 'dark' : 'light'))
const currentFolder = computed(
  () => folders.value.find((folder) => folder.id === folderId.value)?.name ?? '未分类',
)
const stateLabel = computed(() => {
  switch (saveState.value) {
    case 'saving':
      return '同步中…'
    case 'synced':
      return '已同步'
    case 'conflict':
      return '冲突'
    case 'offline':
      return '离线（本地已保存）'
    case 'error':
      return '保存失败'
    default:
      return selected.value?.contentLoaded ? '已缓存' : '未拉取'
  }
})
const stateClass = computed(() =>
  saveState.value === 'conflict'
    ? 'text-red-400'
    : saveState.value === 'offline' || saveState.value === 'error'
      ? 'text-amber-400'
      : 'text-app-muted',
)

function applyTheme(): void {
  document.documentElement.classList.toggle('dark', isDark.value)
  document.documentElement.classList.toggle('light', !isDark.value)
}

function toggleTheme(): void {
  isDark.value = !isDark.value
  applyTheme()
}

async function bootstrap(): Promise<void> {
  try {
    session.value = await AuthService.Current()
    if (session.value) {
      status.value = await SystemService.Status()
      await refreshAll(true)
    }
  } catch (err) {
    error.value = String(err)
  }
}

async function onLoggedIn(value: Session): Promise<void> {
  session.value = value
  status.value = await SystemService.Status()
  await refreshAll(true)
}

async function refreshAll(resetSelection = false): Promise<void> {
  if (syncing.value) {
    return
  }
  syncing.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await SyncService.Refresh()
    if (result) {
      message.value = `索引已同步：随手记 ${result.notes} · 文章 ${result.posts} · 文件夹 ${result.folders}`
      pendingOutbox.value = result.pending
    }
  } catch (err) {
    // 离线 / 服务端暂不可用：仍然展示本地索引，保证离线可用。
    error.value = String(err)
  } finally {
    syncing.value = false
  }
  try {
    folders.value = (await NotesService.Folders()) ?? []
    await loadList(resetSelection)
  } catch (err) {
    error.value = String(err)
  }
}

async function loadList(resetSelection = false): Promise<void> {
  try {
    notes.value = (await NotesService.List(kind.value, folderId.value, keyword.value)) ?? []
    if (resetSelection) {
      selected.value = null
    }
  } catch (err) {
    error.value = String(err)
  }
}

async function selectNote(note: NoteRow): Promise<void> {
  if (opening.value) {
    return
  }
  if (selected.value && selected.value.id !== note.id && dirty.value) {
    await saveNow(selected.value.id)
  }
  opening.value = true
  error.value = ''
  try {
    const opened = (await NotesService.Open(note.id)) ?? note
    selected.value = opened
    loadingEditor = true
    editTitle.value = opened.title
    editContent.value = opened.content
    loadingEditor = false
    dirty.value = false
    saveState.value = 'idle'
    saveMessage.value = ''
    const index = notes.value.findIndex((item) => item.id === opened.id)
    if (index >= 0) {
      notes.value[index] = opened
    }
  } catch (err) {
    error.value = String(err)
  } finally {
    opening.value = false
  }
}

let saveTimer: number | undefined
let pendingId = ''
let loadingEditor = false

function scheduleSave(): void {
  if (!selected.value) {
    return
  }
  pendingId = selected.value.id
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => void saveNow(pendingId), 800)
}

function applySaveResult(result: Awaited<ReturnType<typeof NotesService.Save>>): void {
  if (!result) {
    return
  }
  if (result.note) {
    selected.value = result.note
    const index = notes.value.findIndex((item) => item.id === result.note!.id)
    if (index >= 0) {
      notes.value[index] = result.note
    }
  }
  remoteTitle.value = result.remoteTitle
  remoteVersion.value = result.remoteVersion
  switch (result.state) {
    case 'synced':
      saveState.value = 'synced'
      saveMessage.value = ''
      remoteSnapshot.value = null
      diffOpen.value = false
      break
    case 'conflict':
      saveState.value = 'conflict'
      saveMessage.value = result.message
      remoteSnapshot.value = result.remote ?? null
      dirty.value = true
      break
    case 'offline':
      saveState.value = 'offline'
      saveMessage.value = result.message
      dirty.value = true
      break
    default:
      saveState.value = 'error'
      saveMessage.value = result.message
      dirty.value = true
  }
}

async function saveNow(id: string): Promise<void> {
  if (!selected.value || selected.value.id !== id) {
    return
  }
  window.clearTimeout(saveTimer)
  dirty.value = false
  saveState.value = 'saving'
  try {
    const result = await NotesService.Save({
      id,
      title: editTitle.value,
      content: editContent.value,
    })
    applySaveResult(result)
  } catch (err) {
    saveState.value = 'error'
    saveMessage.value = String(err)
    dirty.value = true
  }
}

async function resolveConflict(side: 'local' | 'remote'): Promise<void> {
  if (!selected.value) {
    return
  }
  saveState.value = 'saving'
  try {
    const result = await NotesService.Resolve(selected.value.id, side)
    if (result?.state === 'synced' && result.note) {
      loadingEditor = true
      editTitle.value = result.note.title
      editContent.value = result.note.content
      loadingEditor = false
      dirty.value = false
    }
    applySaveResult(result)
    if (result?.state === 'synced') {
      await loadList()
    }
  } catch (err) {
    saveState.value = 'error'
    saveMessage.value = String(err)
  }
}

function lineDiff(before: string, after: string): DiffLine[] {
  const a = before.split(/\r?\n/)
  const b = after.split(/\r?\n/)
  const n = a.length
  const m = b.length
  if (n * m > 250000) {
    return [
      ...a.map((text): DiffLine => ({ type: 'del', text })),
      ...b.map((text): DiffLine => ({ type: 'add', text })),
    ]
  }
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: a[i] })
      i++
    } else {
      out.push({ type: 'add', text: b[j] })
      j++
    }
  }
  while (i < n) {
    out.push({ type: 'del', text: a[i++] })
  }
  while (j < m) {
    out.push({ type: 'add', text: b[j++] })
  }
  return out
}

const diffLines = computed<DiffLine[]>(() => {
  if (!diffOpen.value || !remoteSnapshot.value) {
    return []
  }
  return lineDiff(remoteSnapshot.value.content, editContent.value)
})

async function newNote(): Promise<void> {
  if (creatingNote.value) {
    return
  }
  creatingNote.value = true
  try {
    const result = await NotesService.Create({
      kind: kind.value,
      title: '未命名',
      content: '',
      folderId: kind.value === 'NOTE' ? folderId.value : '',
    })
    if (!result?.note) {
      error.value = result?.message ?? '创建失败'
      return
    }
    await refreshPending()
    await loadList()
    await selectNote(result.note)
    message.value = result.message
  } catch (err) {
    error.value = String(err)
  } finally {
    creatingNote.value = false
  }
}

async function reloadFolders(): Promise<void> {
  folders.value = (await FoldersService.List()) ?? []
}

async function refreshPending(): Promise<void> {
  pendingOutbox.value = (await SyncService.Pending()) ?? 0
}

async function createFolder(): Promise<void> {
  const name = newFolderName.value.trim()
  if (!name) {
    creatingFolder.value = false
    return
  }
  try {
    const result = await FoldersService.Create(name)
    if (result?.state !== 'ok') {
      error.value = result?.message ?? '创建文件夹失败'
      return
    }
    creatingFolder.value = false
    newFolderName.value = ''
    await reloadFolders()
    message.value = '文件夹已创建'
  } catch (err) {
    error.value = String(err)
  }
}

function startRenameFolder(folder: FolderRow): void {
  renamingId.value = folder.id
  renameValue.value = folder.name
}

async function submitRenameFolder(folder: FolderRow): Promise<void> {
  const name = renameValue.value.trim()
  if (!name || name === folder.name) {
    renamingId.value = ''
    return
  }
  try {
    const result = await FoldersService.Rename(folder.id, name)
    if (result?.state === 'error') {
      error.value = result.message
      return
    }
    if (result?.message) {
      message.value = result.message
    }
    renamingId.value = ''
    await refreshPending()
    await reloadFolders()
    await loadList()
  } catch (err) {
    error.value = String(err)
  }
}

async function deleteFolder(folder: FolderRow): Promise<void> {
  try {
    const result = await FoldersService.Delete(folder.id)
    if (result?.state === 'error') {
      error.value = result.message
      return
    }
    if (folderId.value === folder.id) {
      folderId.value = ''
    }
    await refreshPending()
    await reloadFolders()
    await loadList(true)
    message.value = result?.message ?? ''
  } catch (err) {
    error.value = String(err)
  }
}

async function closeNote(): Promise<void> {
  if (selected.value && dirty.value) {
    await saveNow(selected.value.id)
  }
  selected.value = null
  loadingEditor = true
  editTitle.value = ''
  editContent.value = ''
  loadingEditor = false
  dirty.value = false
  saveState.value = 'idle'
  saveMessage.value = ''
  remoteSnapshot.value = null
  diffOpen.value = false
}

async function deleteNote(): Promise<void> {  if (!selected.value) {
    return
  }
  try {
    const result = await NotesService.Delete(selected.value.id)
    if (result?.state === 'error') {
      error.value = result.message
      return
    }
    selected.value = null
    await refreshPending()
    await loadList(true)
    message.value = result?.message ?? ''
  } catch (err) {
    error.value = String(err)
  }
}

async function resolveDelete(side: 'local' | 'remote'): Promise<void> {
  if (!selected.value) {
    return
  }
  try {
    const result = await NotesService.ResolveDelete(selected.value.id, side)
    selected.value = null
    await refreshPending()
    await loadList(true)
    message.value = result?.message ?? ''
  } catch (err) {
    error.value = String(err)
  }
}

async function retryOutbox(): Promise<void> {
  try {
    const result = await SyncService.Flush()
    pendingOutbox.value = result?.pending ?? 0
    if ((result?.replayed ?? 0) > 0) {
      await refreshAll()
      message.value = `已重试 ${result?.replayed} 项`
    } else {
      message.value = '没有到期的重试项'
    }
    await refreshPending()
  } catch (err) {
    error.value = String(err)
  }
}

watch(
  [editTitle, editContent],
  () => {
    if (loadingEditor || !selected.value) {
      return
    }
    dirty.value = true
    saveState.value = 'idle'
    scheduleSave()
  },
  { flush: 'sync' },
)

function switchKind(next: Kind): void {
  if (kind.value === next) {
    return
  }
  kind.value = next
  folderId.value = ''
  selected.value = null
  void loadList(true)
}

async function logout(): Promise<void> {
  try {
    await AuthService.Logout()
  } catch (err) {
    error.value = String(err)
  }
  session.value = null
  status.value = null
  notes.value = []
  folders.value = []
  selected.value = null
  message.value = ''
}

let searchTimer: number | undefined
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void loadList(), 250)
})

watch(folderId, () => {
  selected.value = null
  void loadList(true)
})

function formatTime(value: number): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`
}

function publishLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿'
    case 'PUBLISHED':
      return '已发布'
    case 'UNPUBLISHED':
      return '已下架'
    default:
      return status
  }
}

async function openAdmin(): Promise<void> {
  if (!selected.value) {
    return
  }
  try {
    await SystemService.OpenAdmin(`/posts/${selected.value.id}`)
  } catch (err) {
    error.value = String(err)
  }
}

applyTheme()
onMounted(bootstrap)
</script>

<template>
  <div class="flex h-full flex-col bg-app-bg text-app-fg">
    <template v-if="!session">
      <LoginView @logged-in="onLoggedIn" />
    </template>

    <template v-else>
      <header class="flex h-14 shrink-0 items-center justify-between border-b border-app-border px-4">
        <div class="flex items-center gap-3">
          <span class="text-base font-semibold">Simple Notes</span>
          <span class="rounded bg-brand-500/10 px-2 py-0.5 text-xs text-brand-400">
            M4 · 发布状态 · 管理端深链
          </span>
          <span class="text-xs text-app-muted">
            {{ status?.nickname || status?.username }} @ {{ status?.serverUrl }}
          </span>
        </div>
        <div class="flex items-center gap-2">
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel disabled:opacity-60"
            type="button"
            :disabled="syncing"
            @click="refreshAll()"
          >
            {{ syncing ? '同步中…' : '同步索引' }}
          </button>
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
            type="button"
            @click="toggleTheme"
          >
            {{ isDark ? '浅色' : '深色' }}
          </button>
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
            type="button"
            @click="logout"
          >
            退出
          </button>
        </div>
      </header>

      <main class="grid min-h-0 flex-1 grid-cols-[180px_320px_minmax(0,1fr)] gap-3 p-3">
        <aside class="flex min-h-0 flex-col gap-3 overflow-y-auto">
          <section class="rounded-lg border border-app-border bg-app-panel p-2 text-sm">
            <p class="px-2 py-1 text-[11px] uppercase tracking-wide text-app-muted">内容</p>
            <button
              class="mt-1 w-full rounded px-2 py-1.5 text-left text-sm"
              :class="kind === 'NOTE' ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
              type="button"
              @click="switchKind('NOTE')"
            >
              随手记
            </button>
            <button
              class="w-full rounded px-2 py-1.5 text-left text-sm"
              :class="kind === 'POST' ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
              type="button"
              @click="switchKind('POST')"
            >
              博客文章
            </button>
            <p class="mt-2 px-2 py-1 text-[11px] text-app-muted">回收站 / 设置随 M2+ 接入</p>
          </section>

          <section v-if="kind === 'NOTE'" class="rounded-lg border border-app-border bg-app-panel p-2">
            <div class="flex items-center justify-between px-2 py-1">
              <p class="text-[11px] uppercase tracking-wide text-app-muted">文件夹</p>
              <button
                class="rounded px-1.5 text-sm leading-none text-app-muted hover:text-brand-400"
                title="新建文件夹"
                type="button"
                @click="creatingFolder = true"
              >
                +
              </button>
            </div>
            <div v-if="creatingFolder" class="px-2 pb-1">
              <input
                v-model="newFolderName"
                data-testid="new-folder"
                class="w-full rounded border border-brand-400/60 bg-app-bg px-2 py-1 text-xs outline-none"
                placeholder="文件夹名称，回车确认"
                @keydown.enter="createFolder"
                @keydown.esc="creatingFolder = false"
              />
            </div>
            <div
              class="flex items-center gap-1 rounded"
              :class="folderId === '' ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
            >
              <button
                class="min-w-0 flex-1 truncate rounded px-2 py-1.5 text-left text-sm"
                type="button"
                @click="folderId = ''"
              >
                未分类
              </button>
            </div>
            <div
              v-for="folder in folders"
              :key="folder.id"
              class="group flex items-center gap-1 rounded"
              :class="folderId === folder.id ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
            >
              <button
                v-if="renamingId !== folder.id"
                class="min-w-0 flex-1 truncate rounded px-2 py-1.5 text-left text-sm"
                type="button"
                @click="folderId = folder.id"
              >
                {{ folder.name }}
              </button>
              <input
                v-else
                v-model="renameValue"
                class="min-w-0 flex-1 rounded border border-brand-400/60 bg-app-bg px-2 py-1 text-xs outline-none"
                @keydown.enter="submitRenameFolder(folder)"
                @keydown.esc="renamingId = ''"
                @blur="submitRenameFolder(folder)"
              />
              <button
                v-if="renamingId !== folder.id"
                class="hidden px-1 text-[10px] text-app-muted group-hover:block hover:text-brand-400"
                type="button"
                title="重命名"
                @click="startRenameFolder(folder)"
              >
                改
              </button>
              <button
                class="hidden px-1 text-[10px] text-red-400 group-hover:block"
                type="button"
                title="删除"
                @click="deleteFolder(folder)"
              >
                删
              </button>
            </div>
          </section>
        </aside>

        <section class="flex min-h-0 flex-col rounded-lg border border-app-border bg-app-panel">
          <div class="flex items-center gap-2 border-b border-app-border p-2">
            <input
              v-model="keyword"
              class="min-w-0 flex-1 rounded border border-app-border bg-app-bg px-2.5 py-1.5 text-sm outline-none focus:border-brand-400"
              placeholder="搜索标题（本地索引）"
              spellcheck="false"
            />
            <button
              class="shrink-0 rounded border border-app-border px-2.5 py-1.5 text-xs hover:bg-app-bg disabled:opacity-60"
              type="button"
              :disabled="creatingNote"
              @click="newNote"
            >
              + 新建
            </button>
          </div>
          <div class="min-h-0 flex-1 overflow-y-auto">
            <button
              v-for="note in notes"
              :key="note.id"
              :data-note-id="note.id"
              class="block w-full border-b border-app-border/60 px-3 py-2.5 text-left last:border-b-0"
              :class="selected?.id === note.id ? 'bg-brand-500/10' : 'hover:bg-app-bg'"
              type="button"
              @click="selectNote(note)"
            >
              <div class="flex items-center gap-1.5">
                <span v-if="note.pinned" class="text-[10px] text-brand-400">置顶</span>
                <span class="truncate text-sm">{{ note.title }}</span>
                <span
                  class="ml-auto shrink-0 rounded px-1.5 py-0.5 text-[10px]"
                  :class="
                    note.localOnly
                      ? 'bg-violet-500/10 text-violet-300'
                      : note.conflict
                        ? 'bg-red-500/10 text-red-400'
                        : note.dirty
                          ? 'bg-amber-500/10 text-amber-400'
                          : note.contentLoaded
                            ? 'bg-emerald-500/10 text-emerald-400'
                            : 'bg-app-bg text-app-muted'
                  "
                >
                  {{
                    note.localOnly
                      ? '本地'
                      : note.conflict
                        ? '冲突'
                        : note.dirty
                          ? '未同步'
                          : note.contentLoaded
                            ? '已缓存'
                            : '未拉取'
                  }}
                </span>
              </div>
              <div class="mt-1 flex items-center gap-2 text-[11px] text-app-muted">
                <span>{{ formatTime(note.updatedAt) }}</span>
                <span>v{{ note.remoteVersion }}</span>
                <span v-if="note.kind === 'POST' && note.publishStatus">{{ publishLabel(note.publishStatus) }}</span>
                <span v-if="note.kind === 'POST' && note.hasUnpublishedChanges" class="text-amber-400">有未发布更改</span>
                <span v-if="note.tags?.length" class="truncate">{{ note.tags.join(' · ') }}</span>
              </div>
            </button>
            <p v-if="!notes.length" class="p-4 text-xs text-app-muted">
              暂无内容；点击右上角「同步索引」或切换文件夹。
            </p>
          </div>
        </section>

        <section class="flex min-h-0 flex-col rounded-lg border border-app-border bg-app-panel">
          <template v-if="selected">
            <div class="border-b border-app-border px-4 py-2">
              <input
                v-model="editTitle"
                data-testid="note-title"
                class="w-full rounded border border-transparent bg-transparent px-1 py-1 text-sm font-medium outline-none hover:border-app-border focus:border-brand-400"
                placeholder="标题"
              />
              <p class="mt-1 flex flex-wrap items-center gap-2 px-1 text-[11px] text-app-muted">
                <span>{{ selected.kind === 'POST' ? '博客文章' : currentFolder }}</span>
                <span>v{{ selected.remoteVersion }}</span>
                <span v-if="selected.kind === 'POST' && selected.publishStatus">{{ publishLabel(selected.publishStatus) }}</span>
                <span
                  v-if="selected.kind === 'POST' && selected.hasUnpublishedChanges"
                  class="text-amber-400"
                >
                  有未发布更改
                </span>
                <span v-if="selected.tags?.length">{{ selected.tags.join(' · ') }}</span>
                <span v-if="selected.localOnly" class="text-violet-300">本地</span>
                <span class="ml-auto" :class="stateClass">{{ stateLabel }}</span>
                <button
                  v-if="selected.kind === 'POST' && !selected.localOnly"
                  class="rounded border border-brand-400/50 px-1.5 py-0.5 text-[10px] text-brand-400 hover:bg-brand-500/10"
                  type="button"
                  @click="openAdmin"
                >
                  管理端发布
                </button>
                <button
                  class="rounded border border-app-border px-1.5 py-0.5 text-[10px] text-app-muted hover:bg-app-bg"
                  type="button"
                  title="关闭（释放编辑器）"
                  @click="closeNote"
                >
                  关闭
                </button>
                <button
                  class="rounded border border-app-border px-1.5 py-0.5 text-[10px] text-app-muted hover:border-red-500/40 hover:text-red-400"
                  type="button"
                  @click="deleteNote"
                >
                  删除
                </button>
              </p>
            </div>

            <div
              v-if="selected.remoteDeleted && selected.dirty"
              class="border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
            >
              <p>服务端已删除这篇内容，本地有未推送修改。</p>
              <div class="mt-2 flex gap-2">
                <button
                  class="rounded border border-red-500/40 px-2.5 py-1 text-[11px] hover:bg-red-500/10"
                  type="button"
                  @click="resolveDelete('local')"
                >
                  保留为本地副本
                </button>
                <button
                  class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-fg hover:bg-app-bg"
                  type="button"
                  @click="resolveDelete('remote')"
                >
                  放弃本地修改
                </button>
              </div>
            </div>

            <div
              v-if="saveState === 'conflict'"
              class="border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
            >
              <p>
                服务端已有新版本（{{ remoteTitle || '未命名' }} · v{{ remoteVersion }}），本地修改尚未覆盖。
              </p>
              <div class="mt-2 flex gap-2">
                <button
                  class="rounded border border-red-500/40 px-2.5 py-1 text-[11px] hover:bg-red-500/10"
                  type="button"
                  @click="resolveConflict('local')"
                >
                  以本地为准
                </button>
                <button
                  class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-fg hover:bg-app-bg"
                  type="button"
                  @click="resolveConflict('remote')"
                >
                  以服务端为准
                </button>
                <button
                  class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-muted hover:bg-app-bg"
                  type="button"
                  @click="diffOpen = true"
                >
                  查看差异
                </button>
              </div>
            </div>

            <p
              v-if="saveMessage && saveState !== 'conflict'"
              class="px-4 pt-2 text-[11px] text-amber-400"
            >
              {{ saveMessage }}
            </p>

            <div class="min-h-0 flex-1 overflow-hidden p-3">
              <p v-if="opening" class="text-xs text-app-muted">正在拉取正文…</p>
              <MarkdownEditor
                v-else
                v-model="editContent"
                :theme="editorTheme"
                placeholder="开始写作…"
              />
            </div>
          </template>
          <div v-else class="flex h-full items-center justify-center p-6 text-center text-xs text-app-muted">
            选择左侧内容查看；正文在打开时才从服务端拉取并缓存。
          </div>
        </section>
      </main>

      <footer class="flex h-9 shrink-0 items-center gap-3 border-t border-app-border px-4 text-[11px]">
        <span v-if="pendingOutbox" class="text-amber-400">
          待重试 {{ pendingOutbox }} 项
          <button class="ml-1 underline hover:text-amber-300" type="button" @click="retryOutbox">重试</button>
        </span>
        <span v-if="message" class="text-emerald-400">{{ message }}</span>
        <span v-if="error" class="text-red-400">{{ error }}</span>
        <span class="ml-auto text-app-muted">
          {{ status?.databasePath }} · 令牌：{{ status?.hasToken ? '已保存' : '无' }}
        </span>
      </footer>

      <div
        v-if="diffOpen && remoteSnapshot"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6"
      >
        <div class="flex max-h-[85vh] w-full max-w-5xl flex-col rounded-xl border border-app-border bg-app-panel">
          <header class="flex items-start justify-between border-b border-app-border px-4 py-3">
            <div>
              <h3 class="text-sm font-medium">冲突对比</h3>
              <p class="mt-0.5 text-[11px] text-app-muted">
                服务端 v{{ remoteSnapshot.version }} ↔ 本地；红色为服务端独有，绿色为本地独有
              </p>
            </div>
            <button
              class="rounded border border-app-border px-2 py-1 text-xs hover:bg-app-bg"
              type="button"
              @click="diffOpen = false"
            >
              关闭
            </button>
          </header>
          <div class="border-b border-app-border px-4 py-2 text-xs">
            <span class="text-app-muted">标题：</span>
            <span class="text-red-300">{{ remoteSnapshot.title }}</span>
            <span class="mx-1 text-app-muted">→</span>
            <span class="text-emerald-300">{{ editTitle }}</span>
          </div>
          <div class="min-h-0 flex-1 overflow-auto bg-app-bg px-4 py-2 font-mono text-[12px] leading-5">
            <div
              v-for="(line, index) in diffLines"
              :key="index"
              class="whitespace-pre-wrap"
              :class="
                line.type === 'del'
                  ? 'bg-red-500/10 text-red-300'
                  : line.type === 'add'
                    ? 'bg-emerald-500/10 text-emerald-300'
                    : 'text-app-muted'
              "
            >
              <span class="mr-2 inline-block w-4 select-none text-right opacity-60">{{
                line.type === 'del' ? '-' : line.type === 'add' ? '+' : ' '
              }}</span>{{ line.text || ' ' }}
            </div>
          </div>
          <footer class="flex items-center justify-end gap-2 border-t border-app-border px-4 py-3">
            <button
              class="rounded border border-brand-400/60 px-3 py-1 text-xs text-brand-400 hover:bg-brand-500/10"
              type="button"
              @click="resolveConflict('local')"
            >
              以本地为准
            </button>
            <button
              class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-bg"
              type="button"
              @click="resolveConflict('remote')"
            >
              以服务端为准
            </button>
          </footer>
        </div>
      </div>
    </template>
  </div>
</template>
