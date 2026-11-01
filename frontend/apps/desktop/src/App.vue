<script setup lang="ts">
import type { Session } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import { Service as AuthService } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth'
import { Service as NotesService } from '../bindings/cc.naihao/simplenotes/desktop/internal/notes'
import type { RemoteSnapshot } from '../bindings/cc.naihao/simplenotes/desktop/internal/notes/models'
import { Service as FoldersService } from '../bindings/cc.naihao/simplenotes/desktop/internal/folders'
import { Service as LocalFileService } from '../bindings/cc.naihao/simplenotes/desktop/internal/localfile'
import type { LocalFile, RecentFile } from '../bindings/cc.naihao/simplenotes/desktop/internal/localfile/models'
import type { FolderRow, NoteRow } from '../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import { Service as SyncService } from '../bindings/cc.naihao/simplenotes/desktop/internal/syncsvc'
import { Service as SystemService } from '../bindings/cc.naihao/simplenotes/desktop/internal/system'
import type { Status } from '../bindings/cc.naihao/simplenotes/desktop/internal/system/models'
import { Events } from '@wailsio/runtime'
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import ImportDialog from './components/ImportDialog.vue'
import LoginView from './components/LoginView.vue'

// 编辑器（Vditor + Lute）按需加载：列表态不解析 400KB+ 的编辑器代码，降低启动与空闲内存。
const MarkdownEditor = defineAsyncComponent(() => import('./components/MarkdownEditor.vue'))

const LOCAL_FILES_FOLDER = 'local-files'
const UNCATEGORIZED_FOLDER = 'uncategorized'

type Kind = 'NOTE' | 'POST'
type TempState = 'idle' | 'saving' | 'saved' | 'modified' | 'missing' | 'error'
type ClosePrompt = null | 'temp' | 'app'

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
const editFolderId = ref('')
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

// 本地模式 / 临时文件 / 最近打开
const loginOpen = ref(false)
const tempFile = ref<LocalFile | null>(null)
const tempContent = ref('')
const tempDirty = ref(false)
const tempState = ref<TempState>('idle')
const tempMessage = ref('')
const loadingTemp = ref(false)
const recent = ref<RecentFile[]>([])
const openMenu = ref(false)
const importOpen = ref(false)
const importTitle = ref('')
const importing = ref(false)
const promoteOpen = ref(false)
const promoteNote = ref<NoteRow | null>(null)
const promoteFolderId = ref('')
const promoting = ref(false)
const closePrompt = ref<ClosePrompt>(null)
const listMenu = ref<{ x: number; y: number; note: NoteRow } | null>(null)
const exporting = ref(false)

interface DiffLine {
  type: 'same' | 'add' | 'del'
  text: string
}
const message = ref('')
const error = ref('')
const online = ref(navigator.onLine)
const isDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)

const editorTheme = computed(() => (isDark.value ? 'dark' : 'light'))
const currentFolder = computed(() => {
  const id = selected.value?.folderId ?? folderId.value
  if (!id || id === UNCATEGORIZED_FOLDER) {
    return '未分类'
  }
  return folders.value.find((folder) => folder.id === id)?.name ?? '未分类'
})
const stateLabel = computed(() => {
  if (selected.value?.localOnly && saveState.value !== 'saving') {
    return '本地'
  }
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
      if (selected.value?.localOnly) {
        return '本地'
      }
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
function isServerFolder(id: string): boolean {
  return id !== '' && id !== LOCAL_FILES_FOLDER && id !== UNCATEGORIZED_FOLDER
}
const serverFolders = computed(() => folders.value.filter((folder) => isServerFolder(folder.id)))
// 「全部笔记 / 未分类」是固定预设项，不依赖 Go 侧返回；Go 返回对应行时补上数量。
// noteCount = -1 表示当前拿不到统计（例如浏览器预览 / 旧进程），UI 不显示数字。
const displayFolders = computed<FolderRow[]>(() => {
  const all: FolderRow = {
    id: '', name: '全部笔记', sort: -102, noteCount: -1, createTime: 0, updatedAt: 0,
  }
  const uncategorized: FolderRow = {
    id: UNCATEGORIZED_FOLDER, name: '未分类', sort: -101, noteCount: -1, createTime: 0, updatedAt: 0,
  }
  const rest: FolderRow[] = []
  for (const folder of folders.value) {
    if (folder.id === '') {
      all.noteCount = folder.noteCount
    } else if (folder.id === UNCATEGORIZED_FOLDER) {
      uncategorized.noteCount = folder.noteCount
    } else {
      rest.push(folder)
    }
  }
  return [all, uncategorized, ...rest]
})

/** 除「全部 / 未分类」外的真实分组（服务端文件夹 + 本地文件）。 */
const customFolders = computed(() =>
  displayFolders.value.filter((folder) => folder.id !== '' && folder.id !== UNCATEGORIZED_FOLDER),
)
const editorFolderOptions = computed(() => {
  const note = selected.value
  if (!note || note.kind !== 'NOTE') {
    return []
  }
  if (note.localOnly) {
    return [
      { id: '', name: '未分类' },
      ...folders.value.filter((folder) => folder.id === LOCAL_FILES_FOLDER),
    ]
  }
  return [{ id: '', name: '未分类' }, ...serverFolders.value]
})
const unsaved = computed(
  () => tempDirty.value || (!!selected.value && (dirty.value || saveState.value === 'saving')),
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
  } catch (err) {
    error.value = String(err)
  }
  // 默认就是本地 Markdown 编辑器：没有会话时直接进入本地模式，不再用登录页拦截。
  if (session.value) {
    try {
      status.value = await SystemService.Status()
    } catch (err) {
      error.value = String(err)
    }
  }
  await refreshAll(true)
  await loadRecent()
}

function openLogin(): void {
  loginOpen.value = true
}

function closeLogin(): void {
  loginOpen.value = false
}

async function enterLocalMode(): Promise<void> {
  session.value = null
  status.value = null
  kind.value = 'NOTE'
  folderId.value = ''
  selected.value = null
  creatingFolder.value = false
  renamingId.value = ''
  message.value = ''
  error.value = ''
  await refreshAll(true)
}

async function onLoggedIn(value: Session): Promise<void> {
  loginOpen.value = false
  session.value = value
  kind.value = 'NOTE'
  folderId.value = ''
  status.value = await SystemService.Status()
  await refreshAll(true)
}

async function refreshAll(resetSelection = false): Promise<void> {
  if (!session.value) {
    try {
      await reloadFolders()
      await loadList(resetSelection)
    } catch (err) {
      error.value = String(err)
    }
    return
  }
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
    await reloadFolders()
    await loadList(resetSelection)
  } catch (err) {
    error.value = String(err)
  }
}

async function loadList(resetSelection = false): Promise<void> {
  try {
    notes.value = (await NotesService.List(kind.value, folderId.value, keyword.value)) ?? []
    if (resetSelection) {
      const current = selected.value
      selected.value = current && notes.value.some((note) => note.id === current.id) ? current : null
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
    editFolderId.value = opened.folderId ?? ''
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

async function saveNow(id: string): Promise<boolean> {
  if (!selected.value || selected.value.id !== id) {
    return true
  }
  window.clearTimeout(saveTimer)
  const previousFolder = selected.value.folderId ?? ''
  dirty.value = false
  saveState.value = 'saving'
  try {
    const result = await NotesService.Save({
      id,
      title: editTitle.value,
      content: editContent.value,
      folderId: selected.value.kind === 'NOTE' ? editFolderId.value : '',
    })
    applySaveResult(result)
    if (result?.note && (result.note.folderId ?? '') !== previousFolder) {
      // 归类变化会影响侧栏数量与当前筛选下的可见性
      void reloadFolders()
      void loadList()
    }
    return !!result && result.state !== 'error'
  } catch (err) {
    saveState.value = 'error'
    saveMessage.value = String(err)
    dirty.value = true
    return false
  }
}

async function flushManaged(): Promise<boolean> {
  if (!selected.value) {
    return true
  }
  if (!dirty.value && saveState.value !== 'saving') {
    return true
  }
  return saveNow(selected.value.id)
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
      editFolderId.value = result.note.folderId ?? ''
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
      folderId:
        session.value && kind.value === 'NOTE' && isServerFolder(folderId.value)
          ? folderId.value
          : '',
    })
    if (!result?.note) {
      error.value = result?.message ?? '创建失败'
      return
    }
    if (folderId.value === LOCAL_FILES_FOLDER) {
      folderId.value = ''
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
  try {
    folders.value = (await NotesService.Folders()) ?? []
    if (folderId.value && !displayFolders.value.some((folder) => folder.id === folderId.value)) {
      folderId.value = ''
    }
  } catch (err) {
    error.value = String(err)
  }
}

async function refreshPending(): Promise<void> {
  if (!session.value) {
    pendingOutbox.value = 0
    return
  }
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
  editFolderId.value = ''
  loadingEditor = false
  dirty.value = false
  saveState.value = 'idle'
  saveMessage.value = ''
  remoteSnapshot.value = null
  diffOpen.value = false
}

function isMarkdownPath(path: string): boolean {
  return /\.(md|markdown)$/i.test(path)
}

function markdownTitle(name: string): string {
  return name.replace(/\.(md|markdown)$/i, '')
}

function applyTempFile(file: LocalFile): void {
  loadingTemp.value = true
  tempFile.value = file
  tempContent.value = file.content
  loadingTemp.value = false
  tempDirty.value = false
  tempState.value = 'idle'
  tempMessage.value = ''
}

async function openFile(asImport = false): Promise<void> {
  openMenu.value = false
  if (tempFile.value && tempDirty.value && !window.confirm('当前临时文件有未保存修改，放弃并打开新文件？')) {
    return
  }
  if (!(await flushManaged())) {
    return
  }
  try {
    const file = await LocalFileService.OpenDialog()
    if (!file) {
      return
    }
    applyTempFile(file)
    await loadRecent()
    if (asImport) {
      openImportDialog()
    }
  } catch (err) {
    error.value = String(err)
  }
}

async function openPath(path: string): Promise<void> {
  openMenu.value = false
  if (tempFile.value && tempDirty.value && !window.confirm('当前临时文件有未保存修改，放弃并打开新文件？')) {
    return
  }
  if (!(await flushManaged())) {
    return
  }
  try {
    const file = await LocalFileService.OpenPath(path)
    if (!file) {
      return
    }
    applyTempFile(file)
    await loadRecent()
  } catch (err) {
    error.value = String(err)
  }
}

async function openRecent(item: RecentFile): Promise<void> {
  if (!item.exists) {
    error.value = `文件不存在：${item.path}`
    return
  }
  await openPath(item.path)
}

async function saveTemp(force = false): Promise<boolean> {
  if (!tempFile.value) {
    return true
  }
  tempState.value = 'saving'
  try {
    const result = await LocalFileService.Save({
      path: tempFile.value.path,
      content: tempContent.value,
      expectedHash: tempFile.value.hash,
      force,
    })
    if (!result) {
      tempState.value = 'error'
      tempMessage.value = '保存失败'
      return false
    }
    switch (result.state) {
      case 'saved':
        tempFile.value = {
          ...tempFile.value,
          path: result.path,
          name: result.name,
          hash: result.hash,
          mtimeMs: result.mtimeMs,
          size: result.size,
          lineEnding: result.lineEnding,
        }
        tempDirty.value = false
        tempState.value = 'saved'
        tempMessage.value = ''
        await loadRecent()
        return true
      case 'modified':
        tempState.value = 'modified'
        tempMessage.value = result.message
        return false
      case 'missing':
        tempState.value = 'missing'
        tempMessage.value = result.message
        return false
      default:
        tempState.value = 'error'
        tempMessage.value = result.message || '保存失败'
        return false
    }
  } catch (err) {
    tempState.value = 'error'
    tempMessage.value = String(err)
    return false
  }
}

async function tempSaveAs(): Promise<void> {
  if (!tempFile.value) {
    return
  }
  try {
    const file = await LocalFileService.SaveAs({
      defaultName: tempFile.value.name,
      content: tempContent.value,
    })
    if (!file) {
      return
    }
    applyTempFile(file)
    tempState.value = 'saved'
    await loadRecent()
    message.value = `已另存为 ${file.path}`
  } catch (err) {
    tempState.value = 'error'
    tempMessage.value = String(err)
  }
}

async function tempReload(): Promise<void> {
  if (!tempFile.value) {
    return
  }
  if (tempDirty.value && !window.confirm('放弃未保存修改并重新加载？')) {
    return
  }
  try {
    const file = await LocalFileService.Read(tempFile.value.path)
    if (file) {
      applyTempFile(file)
    }
  } catch (err) {
    tempState.value = 'error'
    tempMessage.value = String(err)
  }
}

function discardTemp(): void {
  tempFile.value = null
  tempContent.value = ''
  tempDirty.value = false
  tempState.value = 'idle'
  tempMessage.value = ''
}

function closeTemp(): void {
  if (tempDirty.value) {
    closePrompt.value = 'temp'
    return
  }
  discardTemp()
}

function openImportDialog(): void {
  if (!tempFile.value) {
    return
  }
  importTitle.value = markdownTitle(tempFile.value.name)
  importOpen.value = true
}

function applySelectedNote(note: NoteRow): void {
  selected.value = note
  loadingEditor = true
  editTitle.value = note.title
  editContent.value = note.content
  editFolderId.value = note.folderId ?? ''
  loadingEditor = false
  dirty.value = false
  saveState.value = 'idle'
  saveMessage.value = ''
  remoteSnapshot.value = null
  diffOpen.value = false
}

async function confirmImport(payload: {
  title: string
  target: 'local' | 'server'
  folderId: string
}): Promise<void> {
  if (!tempFile.value || importing.value) {
    return
  }
  importing.value = true
  try {
    const result = await NotesService.ImportFile({
      title: payload.title,
      content: tempContent.value,
      target: payload.target,
      folderId: payload.folderId,
    })
    if (!result?.note) {
      error.value = result?.message ?? '纳入管理失败'
      return
    }
    importOpen.value = false
    discardTemp()
    kind.value = 'NOTE'
    folderId.value = ''
    keyword.value = ''
    await reloadFolders()
    await loadList()
    applySelectedNote(result.note)
    saveState.value =
      result.state === 'synced' ? 'synced' : result.state === 'offline' ? 'offline' : 'idle'
    saveMessage.value = result.message
    message.value = result.message
  } catch (err) {
    error.value = String(err)
  } finally {
    importing.value = false
  }
}

function startPromote(note: NoteRow): void {
  if (!session.value || !note.localOnly) {
    return
  }
  promoteNote.value = note
  promoteFolderId.value = ''
  promoteOpen.value = true
}

async function confirmPromote(): Promise<void> {
  const note = promoteNote.value
  if (!note || promoting.value) {
    return
  }
  promoting.value = true
  try {
    const result = await NotesService.PromoteLocal(note.id, promoteFolderId.value)
    if (!result?.note) {
      error.value = result?.message ?? '同步到服务端失败'
      return
    }
    promoteOpen.value = false
    if (selected.value?.id === result.note.id) {
      applySelectedNote(result.note)
      saveState.value =
        result.state === 'synced' ? 'synced' : result.state === 'offline' ? 'offline' : 'idle'
      saveMessage.value = result.message
    }
    await reloadFolders()
    await loadList()
    message.value = result.message
  } catch (err) {
    error.value = String(err)
  } finally {
    promoting.value = false
  }
}

async function exportNote(note?: NoteRow): Promise<void> {
  const target = note ?? selected.value
  if (!target || exporting.value) {
    return
  }
  exporting.value = true
  listMenu.value = null
  try {
    let content = target.content
    let title = target.title
    if (selected.value?.id === target.id) {
      content = editContent.value
      title = editTitle.value
    } else {
      const opened = await NotesService.Open(target.id)
      if (!opened) {
        return
      }
      content = opened.content
      title = opened.title
    }
    const result = await LocalFileService.ExportMarkdown({ defaultName: title, content })
    if (result?.path) {
      message.value = `已导出到 ${result.path}`
    }
  } catch (err) {
    error.value = String(err)
  } finally {
    exporting.value = false
  }
}

function openListMenu(note: NoteRow, event: MouseEvent): void {
  listMenu.value = { x: event.clientX, y: event.clientY, note }
}

async function loadRecent(): Promise<void> {
  try {
    recent.value = (await LocalFileService.Recent()) ?? []
  } catch (err) {
    error.value = String(err)
  }
}

async function removeRecent(path: string): Promise<void> {
  try {
    await LocalFileService.RemoveRecent(path)
    await loadRecent()
  } catch (err) {
    error.value = String(err)
  }
}

async function clearRecent(): Promise<void> {
  try {
    await LocalFileService.ClearRecent()
    recent.value = []
  } catch (err) {
    error.value = String(err)
  }
}

async function handleCloseRequest(): Promise<void> {
  if (tempFile.value && tempDirty.value) {
    closePrompt.value = 'app'
    return
  }
  if (!(await flushManaged())) {
    return
  }
  await LocalFileService.ConfirmClose()
}

async function confirmClosePrompt(action: 'save' | 'discard' | 'cancel'): Promise<void> {
  const mode = closePrompt.value
  if (!mode) {
    return
  }
  if (action === 'cancel') {
    closePrompt.value = null
    return
  }
  if (action === 'save') {
    if (tempFile.value && tempDirty.value && !(await saveTemp())) {
      return
    }
    if (!(await flushManaged())) {
      return
    }
  }
  if (action === 'discard' && mode === 'app' && !(await flushManaged())) {
    return
  }
  closePrompt.value = null
  if (mode === 'temp') {
    discardTemp()
    return
  }
  await LocalFileService.ConfirmClose()
}

async function deleteNote(): Promise<void> {
  if (!selected.value) {
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
    await reloadFolders()
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
    await reloadFolders()
    await loadList(true)
    message.value = result?.message ?? ''
  } catch (err) {
    error.value = String(err)
  }
}

async function retryOutbox(): Promise<void> {
  if (!session.value) {
    return
  }
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
  [editTitle, editContent, editFolderId],
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
  if (!session.value && next === 'POST') {
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
  kind.value = 'NOTE'
  folderId.value = ''
  keyword.value = ''
  pendingOutbox.value = 0
  creatingFolder.value = false
  renamingId.value = ''
  message.value = ''
  // 退出登录回到本地模式，而不是登录页：本地笔记与文件编辑继续可用。
  await enterLocalMode()
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

watch(
  tempContent,
  () => {
    if (loadingTemp.value || !tempFile.value) {
      return
    }
    tempDirty.value = true
    if (tempState.value !== 'saving') {
      tempState.value = 'idle'
    }
  },
  { flush: 'sync' },
)

watch(unsaved, (value) => {
  LocalFileService.SetUnsaved(value).catch(() => {})
})

function onKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey)) {
    return
  }
  const key = event.key.toLowerCase()
  if (key === 's') {
    event.preventDefault()
    if (tempFile.value) {
      void saveTemp()
    } else if (selected.value) {
      void saveNow(selected.value.id)
    }
  }
  if (key === 'o') {
    event.preventDefault()
    void openFile()
  }
}

let offDrop: (() => void) | undefined
let offClose: (() => void) | undefined

onMounted(() => {
  applyTheme()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
  LocalFileService.SetUnsaved(false).catch(() => {})
  offDrop = Events.On('app:files-dropped', (event) => {
    const data = event.data as string[] | { filenames?: string[] } | null | undefined
    const filenames = Array.isArray(data) ? data : (data?.filenames ?? [])
    const path = filenames.find(isMarkdownPath)
    if (!path) {
      if (filenames.length) {
        error.value = '只支持打开 .md / .markdown 文件'
      }
      return
    }
    if (filenames.length > 1) {
      message.value = '一次只打开一个文件，已打开第一个 Markdown 文件'
    }
    void openPath(path)
  })
  offClose = Events.On('app:close-requested', () => {
    void handleCloseRequest()
  })
  void bootstrap()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
  offDrop?.()
  offClose?.()
})

function updateOnline(): void {
  online.value = navigator.onLine
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
</script>

<template>
  <div class="flex h-full flex-col bg-app-bg text-app-fg" data-file-drop-target="true">
    <template v-if="tempFile">
      <header class="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-app-border px-4">
        <div class="flex min-w-0 items-center gap-3">
          <span class="text-base font-semibold">Simple Notes</span>
          <span class="shrink-0 rounded bg-amber-500/10 px-2 py-0.5 text-xs text-amber-400">临时编辑</span>
          <span class="truncate text-sm">{{ tempFile.name }}</span>
          <span class="hidden min-w-0 truncate text-[11px] text-app-muted md:inline" :title="tempFile.path">
            {{ tempFile.path }}
          </span>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <span class="text-[11px]" :class="tempDirty ? 'text-amber-400' : 'text-app-muted'">
            {{ tempState === 'saving' ? '保存中…' : tempDirty ? '未保存' : '已保存' }}
          </span>
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel disabled:opacity-60"
            type="button"
            :disabled="tempState === 'saving'"
            @click="saveTemp()"
          >
            保存
          </button>
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
            type="button"
            @click="tempSaveAs"
          >
            另存为…
          </button>
          <button
            class="rounded border border-brand-400/60 px-3 py-1 text-xs text-brand-400 hover:bg-brand-500/10"
            type="button"
            @click="openImportDialog"
          >
            纳入管理…
          </button>
          <button
            v-if="!session"
            class="rounded border border-brand-400/60 px-3 py-1 text-xs text-brand-400 hover:bg-brand-500/10"
            type="button"
            @click="openLogin"
          >
            登录
          </button>
          <button
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
            type="button"
            @click="closeTemp"
          >
            关闭
          </button>
        </div>
      </header>

      <div
        v-if="tempState === 'modified'"
        class="flex flex-wrap items-center gap-2 border-b border-amber-500/30 bg-amber-500/10 px-4 py-2 text-xs text-amber-300"
      >
        <span>文件已被其他程序修改。</span>
        <button class="rounded border border-amber-500/40 px-2 py-1 text-[11px] hover:bg-amber-500/10" type="button" @click="saveTemp(true)">
          覆盖
        </button>
        <button class="rounded border border-app-border px-2 py-1 text-[11px] hover:bg-app-bg" type="button" @click="tempSaveAs">
          另存为
        </button>
        <button class="rounded border border-app-border px-2 py-1 text-[11px] hover:bg-app-bg" type="button" @click="tempReload">
          重新加载
        </button>
      </div>
      <div
        v-else-if="tempState === 'missing'"
        class="flex flex-wrap items-center gap-2 border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
      >
        <span>{{ tempMessage || '文件已被删除。' }}</span>
        <button class="rounded border border-red-500/40 px-2 py-1 text-[11px] hover:bg-red-500/10" type="button" @click="tempSaveAs">
          另存为
        </button>
      </div>
      <div
        v-else-if="tempState === 'error'"
        class="border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
      >
        {{ tempMessage || '保存失败' }}
      </div>

      <main class="min-h-0 flex-1 p-3">
        <section class="flex h-full min-h-0 flex-col rounded-lg border border-app-border bg-app-panel">
          <div class="min-h-0 flex-1 overflow-hidden p-3">
            <MarkdownEditor v-model="tempContent" :theme="editorTheme" placeholder="开始写作…" />
          </div>
        </section>
      </main>

      <footer class="flex h-9 shrink-0 items-center gap-3 border-t border-app-border px-4 text-[11px] text-app-muted">
        <span>临时编辑不会进入列表，也不参与同步；关闭前请保存。</span>
        <span class="ml-auto">
          {{ tempFile.size }} B · {{ tempFile.lineEnding === 'crlf' ? 'CRLF' : 'LF' }}
        </span>
      </footer>
    </template>

    <template v-else>
      <header class="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-app-border px-4">
        <div class="flex min-w-0 items-center gap-3">
          <span class="text-base font-semibold">Simple Notes</span>
          <span
            class="shrink-0 rounded px-2 py-0.5 text-xs"
            :class="session ? 'bg-brand-500/10 text-brand-400' : 'bg-amber-500/10 text-amber-400'"
          >
            {{ session ? 'M4 · 发布状态 · 管理端深链' : '本地模式 · 不连接服务器' }}
          </span>
          <span v-if="session" class="truncate text-xs text-app-muted">
            {{ status?.nickname || status?.username }} @ {{ status?.serverUrl }}
          </span>
          <span v-else class="text-xs text-app-muted">本地文件与本地笔记随时可用</span>
        </div>
        <div class="flex items-center gap-2">
          <div class="relative">
            <button
              class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
              type="button"
              @click="openMenu = !openMenu"
            >
              打开文件 ▾
            </button>
            <div v-if="openMenu" class="fixed inset-0 z-20" @click="openMenu = false" />
            <div
              v-if="openMenu"
              class="absolute right-0 z-30 mt-1 w-80 rounded-lg border border-app-border bg-app-panel p-1 shadow-xl"
            >
              <button
                class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg"
                type="button"
                @click="openFile()"
              >
                打开文件…（临时编辑）
              </button>
              <button
                class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg"
                type="button"
                @click="openFile(true)"
              >
                打开并纳入管理…
              </button>
              <div class="mt-1 border-t border-app-border pt-1">
                <p class="px-2 py-1 text-[10px] uppercase tracking-wide text-app-muted">最近打开</p>
                <div
                  v-for="item in recent.slice(0, 8)"
                  :key="item.path"
                  class="flex items-center gap-1 rounded hover:bg-app-bg"
                >
                  <button class="min-w-0 flex-1 px-2 py-1 text-left" type="button" @click="openRecent(item)">
                    <span class="block truncate text-xs" :class="item.exists ? 'text-app-fg' : 'text-red-400'">
                      {{ item.name }}
                    </span>
                    <span class="block truncate text-[10px] text-app-muted">{{ item.path }}</span>
                  </button>
                  <button
                    class="shrink-0 px-1.5 text-xs text-app-muted hover:text-red-400"
                    type="button"
                    title="从最近列表移除"
                    @click.stop="removeRecent(item.path)"
                  >
                    ×
                  </button>
                </div>
                <p v-if="!recent.length" class="px-2 py-1 text-[11px] text-app-muted">暂无记录</p>
                <button
                  v-if="recent.length"
                  class="mt-1 w-full rounded px-2 py-1.5 text-left text-[11px] text-app-muted hover:bg-app-bg hover:text-red-400"
                  type="button"
                  @click="clearRecent"
                >
                  清空最近记录
                </button>
              </div>
            </div>
          </div>
          <button
            v-if="session"
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
            v-if="session"
            class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-panel"
            type="button"
            @click="logout"
          >
            退出
          </button>
          <button
            v-else
            class="rounded border border-brand-400/60 px-3 py-1 text-xs text-brand-400 hover:bg-brand-500/10"
            type="button"
            @click="openLogin"
          >
            登录
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
              v-if="session"
              class="w-full rounded px-2 py-1.5 text-left text-sm"
              :class="kind === 'POST' ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
              type="button"
              @click="switchKind('POST')"
            >
              博客文章
            </button>
            <p class="mt-2 px-2 py-1 text-[11px] text-app-muted">
              {{ session ? '回收站随 M2+ 接入' : '本地笔记不参与同步' }}
            </p>
          </section>

          <section v-if="kind === 'NOTE'" class="rounded-lg border border-app-border bg-app-panel p-2">
            <div class="flex items-center justify-between px-2 py-1">
              <p class="text-[11px] uppercase tracking-wide text-app-muted">文件夹</p>
              <button
                v-if="session"
                class="rounded px-1.5 text-sm leading-none text-app-muted hover:text-brand-400 disabled:opacity-40"
                :disabled="!online"
                :title="online ? '新建文件夹' : '离线时不能新建文件夹'"
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
              v-for="folder in displayFolders"
              :key="folder.id || 'all'"
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
              <span
                v-if="renamingId !== folder.id && folder.noteCount >= 0"
                class="shrink-0 text-[10px] text-app-muted"
              >
                {{ folder.noteCount }}
              </span>
              <button
                v-if="session && isServerFolder(folder.id) && renamingId !== folder.id"
                class="hidden px-1 text-[10px] text-app-muted group-hover:block hover:text-brand-400"
                type="button"
                title="重命名"
                @click="startRenameFolder(folder)"
              >
                改
              </button>
              <button
                v-if="session && isServerFolder(folder.id)"
                class="hidden px-1 text-[10px] text-red-400 group-hover:block"
                type="button"
                title="删除"
                @click="deleteFolder(folder)"
              >
                删
              </button>
            </div>
            <p v-if="!customFolders.length" class="px-2 py-1 text-[11px] text-app-muted">暂无文件夹</p>
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
              @contextmenu.prevent="openListMenu(note, $event)"
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
              {{ session ? '暂无内容；点击右上角「同步索引」或切换文件夹。' : '暂无本地笔记；点击「新建」或打开本地文件。' }}
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
                <span v-if="selected.kind === 'POST'">博客文章</span>
                <select
                  v-else
                  v-model="editFolderId"
                  class="rounded border border-app-border bg-app-bg px-1 py-0.5 text-[11px] text-app-fg outline-none focus:border-brand-400"
                  :title="currentFolder"
                >
                  <option
                    v-for="option in editorFolderOptions"
                    :key="option.id || 'uncategorized'"
                    :value="option.id"
                  >
                    {{ option.name }}
                  </option>
                </select>
                <span v-if="!selected.localOnly">v{{ selected.remoteVersion }}</span>
                <span v-if="selected.kind === 'POST' && selected.publishStatus">{{ publishLabel(selected.publishStatus) }}</span>
                <span
                  v-if="selected.kind === 'POST' && selected.hasUnpublishedChanges"
                  class="text-amber-400"
                >
                  有未发布更改
                </span>
                <span v-if="selected.tags?.length">{{ selected.tags.join(' · ') }}</span>
                <span class="ml-auto" :class="stateClass">{{ stateLabel }}</span>
                <button
                  v-if="selected.localOnly && session"
                  class="rounded border border-brand-400/50 px-1.5 py-0.5 text-[10px] text-brand-400 hover:bg-brand-500/10"
                  type="button"
                  @click="startPromote(selected)"
                >
                  同步到服务端
                </button>
                <button
                  v-if="selected.kind === 'POST' && !selected.localOnly"
                  class="rounded border border-brand-400/50 px-1.5 py-0.5 text-[10px] text-brand-400 hover:bg-brand-500/10"
                  type="button"
                  @click="openAdmin"
                >
                  管理端发布
                </button>
                <button
                  class="rounded border border-app-border px-1.5 py-0.5 text-[10px] text-app-muted hover:bg-app-bg disabled:opacity-60"
                  type="button"
                  :disabled="exporting"
                  @click="exportNote(selected)"
                >
                  导出
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
          <div v-else class="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
            <template v-if="session">
              <p class="text-xs text-app-muted">选择左侧内容查看；正文在打开时才从服务端拉取并缓存。</p>
            </template>
            <template v-else>
              <p class="text-sm font-medium">本地 Markdown 编辑器</p>
              <p class="max-w-sm text-xs leading-relaxed text-app-muted">
                当前未登录，可以直接打开本地文件或新建仅本地笔记；登录后才会连接服务器并同步。
              </p>
              <div class="mt-1 flex flex-wrap items-center justify-center gap-2">
                <button
                  class="rounded bg-brand-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-400 disabled:opacity-60"
                  type="button"
                  :disabled="creatingNote"
                  @click="newNote"
                >
                  + 新建本地笔记
                </button>
                <button
                  class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg"
                  type="button"
                  @click="openFile()"
                >
                  打开本地文件…
                </button>
                <button
                  class="rounded border border-app-border px-3 py-1.5 text-xs text-app-muted hover:bg-app-bg"
                  type="button"
                  @click="openLogin"
                >
                  登录
                </button>
              </div>
            </template>
          </div>
        </section>
      </main>

      <footer class="flex h-9 shrink-0 items-center gap-3 border-t border-app-border px-4 text-[11px]">
        <span v-if="session && pendingOutbox" class="text-amber-400">
          待重试 {{ pendingOutbox }} 项
          <button class="ml-1 underline hover:text-amber-300" type="button" @click="retryOutbox">重试</button>
        </span>
        <span v-if="message" class="text-emerald-400">{{ message }}</span>
        <span v-if="error" class="text-red-400">{{ error }}</span>
        <span class="ml-auto text-app-muted">
          {{
            session
              ? `${status?.databasePath ?? ''} · 令牌：${status?.hasToken ? '已保存' : '无'}`
              : '本地模式 · 本地 SQLite · 不连接服务器'
          }}
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

    <ImportDialog
      :open="importOpen"
      :default-title="importTitle"
      :can-server="!!session"
      :folders="serverFolders"
      @confirm="confirmImport"
      @cancel="importOpen = false"
    />

    <div
      v-if="loginOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6"
      @click.self="closeLogin"
    >
      <div class="w-full max-w-sm">
        <LoginView @logged-in="onLoggedIn" @cancel="closeLogin" />
      </div>
    </div>

    <div v-if="promoteOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6">
      <div class="w-full max-w-sm rounded-xl border border-app-border bg-app-panel p-4 shadow-xl">
        <h3 class="text-sm font-medium">同步到服务端</h3>
        <p class="mt-1 text-[11px] leading-relaxed text-app-muted">
          「{{ promoteNote?.title }}」将移动到当前账号；「本地文件」分组会被清空。
        </p>
        <label class="mt-4 block text-xs text-app-muted">
          文件夹
          <select
            v-model="promoteFolderId"
            class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          >
            <option value="">未分类</option>
            <option v-for="folder in serverFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
          </select>
        </label>
        <div class="mt-5 flex justify-end gap-2">
          <button
            class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg"
            type="button"
            @click="promoteOpen = false"
          >
            取消
          </button>
          <button
            class="rounded bg-brand-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-400 disabled:opacity-60"
            type="button"
            :disabled="promoting"
            @click="confirmPromote"
          >
            {{ promoting ? '同步中…' : '同步' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="closePrompt" class="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-6">
      <div class="w-full max-w-sm rounded-xl border border-app-border bg-app-panel p-4 shadow-xl">
        <h3 class="text-sm font-medium">
          {{ closePrompt === 'temp' ? '关闭临时文件？' : '退出 Simple Notes？' }}
        </h3>
        <p class="mt-1 text-[11px] leading-relaxed text-app-muted">
          有未保存的修改。保存后会写回源文件 / 本地数据库；放弃将丢失这些修改。
        </p>
        <div class="mt-5 flex justify-end gap-2">
          <button
            class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg"
            type="button"
            @click="confirmClosePrompt('cancel')"
          >
            取消
          </button>
          <button
            class="rounded border border-app-border px-3 py-1.5 text-xs text-red-400 hover:bg-app-bg"
            type="button"
            @click="confirmClosePrompt('discard')"
          >
            放弃
          </button>
          <button
            class="rounded bg-brand-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-400"
            type="button"
            @click="confirmClosePrompt('save')"
          >
            保存
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="listMenu"
      class="fixed inset-0 z-40"
      @click="listMenu = null"
      @contextmenu.prevent="listMenu = null"
    >
      <div
        class="absolute w-56 rounded-lg border border-app-border bg-app-panel p-1 shadow-xl"
        :style="{ left: `${listMenu.x}px`, top: `${listMenu.y}px` }"
        @click.stop
      >
        <button
          class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg"
          type="button"
          @click="exportNote(listMenu.note)"
        >
          导出为 Markdown…
        </button>
        <button
          v-if="listMenu.note.localOnly && session"
          class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg"
          type="button"
          @click="startPromote(listMenu.note); listMenu = null"
        >
          同步到服务端
        </button>
      </div>
    </div>
  </div>
</template>
