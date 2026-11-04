<script setup lang="ts">
import type { Session } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import { Service as AuthService } from '../bindings/cc.naihao/simplenotes/desktop/internal/auth'
import { Service as NotesService } from '../bindings/cc.naihao/simplenotes/desktop/internal/notes'
import { Service as FoldersService } from '../bindings/cc.naihao/simplenotes/desktop/internal/folders'
import { Service as LocalFileService } from '../bindings/cc.naihao/simplenotes/desktop/internal/localfile'
import { Service as MediaService } from '../bindings/cc.naihao/simplenotes/desktop/internal/media'
import type { LocalFile, RecentFile } from '../bindings/cc.naihao/simplenotes/desktop/internal/localfile/models'
import type { FolderRow, NoteRow } from '../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import { Service as SyncService } from '../bindings/cc.naihao/simplenotes/desktop/internal/syncsvc'
import { Service as SystemService } from '../bindings/cc.naihao/simplenotes/desktop/internal/system'
import { Binding as LogService } from '../bindings/cc.naihao/simplenotes/desktop/internal/logging'
import type { Status } from '../bindings/cc.naihao/simplenotes/desktop/internal/system/models'
import { useBreakpoint } from '@simple-notes/shared'
import { Clipboard, Events } from '@wailsio/runtime'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

import AppHeader from './components/AppHeader.vue'
import ContextMenu from './components/ContextMenu.vue'
import DocumentView from './components/DocumentView.vue'
import ErrorDetailsDialog from './components/ErrorDetailsDialog.vue'
import ImportDialog from './components/ImportDialog.vue'
import LibrarySidebar from './components/LibrarySidebar.vue'
import LoginView from './components/LoginView.vue'
import QuickOpen from './components/QuickOpen.vue'
import StartView from './components/StartView.vue'
import StatusBar from './components/StatusBar.vue'
import TabBar from './components/TabBar.vue'
import UnsavedDialog from './components/UnsavedDialog.vue'
import {
  MAX_TABS,
  fileKey,
  noteKey,
  noteMetaFromRow,
  tabFromNote,
  tabKeyForNote,
  type DocumentTab,
  type FileMeta,
  type PersistedTab,
  type PersistedTabs,
} from './types/document'
import type { LibraryFilter, LibraryItem } from './types/library'
import type { ContextMenuItem } from './types/context-menu'
import type { FolderNode, FolderOption } from './types/folders'
import type { QuickAction, QuickItem } from './types/quick'
import { describeError, type ErrorKind } from './lib/errors'
import { log, logError } from './lib/logger'
import { useTheme } from './composables/useTheme'

type Kind = 'NOTE' | 'POST'
type DocumentAction =
  | 'save'
  | 'save-as'
  | 'import'
  | 'promote'
  | 'export'
  | 'delete'
  | 'close'
  | 'reload'
  | 'force-save'
  | 'diff'
  | 'open-admin'
  | 'resolve-local'
  | 'resolve-remote'
  | 'resolve-delete-local'
  | 'resolve-delete-remote'
type CloseMode = 'tab' | 'app' | 'others' | 'right' | 'all'

interface CloseState {
  mode: CloseMode
  tab?: DocumentTab
  files: DocumentTab[]
}

/** 文本编辑控件右键菜单：记录打开时的焦点元素与选区，菜单点击后恢复。 */
interface TextMenuState {
  x: number
  y: number
  element: HTMLElement
  input?: { start: number; end: number }
  range?: Range
}

const UNCATEGORIZED_FOLDER = 'uncategorized'

const session = ref<Session | null>(null)
const status = ref<Status | null>(null)
const kind = ref<Kind>('NOTE')
const sourceFilter = ref<LibraryFilter>('local')
const folderId = ref('')
const keyword = ref('')
const folders = ref<FolderRow[]>([])
const notes = ref<NoteRow[]>([])
const quickNotes = ref<NoteRow[]>([])
const recent = ref<RecentFile[]>([])
const tabs = ref<DocumentTab[]>([])
const activeKey = ref('')
const syncing = ref(false)
const opening = ref(false)
const pendingOutbox = ref(0)
const creatingNote = ref(false)
const message = ref('')
const error = ref('')
const errorDetail = ref('')
const errorKind = ref<ErrorKind | ''>('')
const errorOpen = ref(false)
const syncError = ref('')
const online = ref(navigator.onLine)
const sidebarOpen = ref(false)
const sidebarWidth = ref(440)
const quickOpen = ref(false)
const loginOpen = ref(false)
const loadingRestore = ref(true)

const importOpen = ref(false)
const importTitle = ref('')
const importSourceKey = ref('')
const importing = ref(false)

const promoteOpen = ref(false)
const promoteTab = ref<DocumentTab | null>(null)
const promoteNoteRow = ref<NoteRow | null>(null)
const promoteFolderId = ref('')
const promoting = ref(false)

const diffOpen = ref(false)
const listMenu = ref<{ x: number; y: number; item: LibraryItem } | null>(null)
const textMenu = ref<TextMenuState | null>(null)
const exporting = ref(false)
const closeDialog = ref<CloseState | null>(null)

const { isDesktop } = useBreakpoint()
const { mode: themeMode, isDark, setMode: setThemeMode } = useTheme()
const editorTheme = computed<'light' | 'dark'>(() => (isDark.value ? 'dark' : 'light'))
const activeTab = computed(() => tabs.value.find((tab) => tab.key === activeKey.value) ?? null)
const unsaved = computed(() => tabs.value.some((tab) => tab.dirty || tab.saveState === 'saving'))
const windowTitle = computed(() => {
  const tab = activeTab.value
  if (!tab) {
    return unsaved.value ? '* Simple Notes' : 'Simple Notes'
  }
  return `${tab.title || '未命名'}${unsaved.value ? ' *' : ''} — Simple Notes`
})

/**
 * 统一处理异常：网络失败只更新离线状态（不进入红色错误区），
 * 其余错误显示短提示，完整错误链进入日志与详情弹层。
 */
function setError(err: unknown, event = 'app', fallback = '操作失败'): void {
  const info = describeError(err, fallback)
  logError(event, err, info.message, { kind: info.kind })
  if (info.kind === 'offline') {
    online.value = false
    return
  }
  error.value = info.message
  errorDetail.value = info.detail
  errorKind.value = info.kind
}

/** 业务结果里的错误文案（没有异常对象可记录）。 */
function showError(message: string, event = 'app'): void {
  log('warn', event, message)
  error.value = message
  errorDetail.value = ''
  errorKind.value = 'local'
}

/** 同步失败：网络问题只改变同步 / 在线状态，其它错误进入状态栏。 */
function reportSyncError(err: unknown, event = 'sync.refresh'): void {
  const info = describeError(err, '同步失败')
  logError(event, err, info.message, { kind: info.kind })
  if (info.kind === 'offline') {
    online.value = false
    syncError.value = ''
    return
  }
  syncError.value = '同步失败'
  error.value = info.message
  errorDetail.value = info.detail
  errorKind.value = info.kind
}

function showErrorDetails(): void {
  if (error.value || errorDetail.value) {
    errorOpen.value = true
  }
}

/** 标签内的短错误：不把绑定层原文塞进文档区。 */
function tabErrorText(err: unknown, event: string): string {
  const info = describeError(err, '操作失败')
  logError(event, err, info.message, { kind: info.kind })
  if (info.kind === 'offline') {
    online.value = false
    return '离线，联网后重试'
  }
  return info.message
}

async function openLogs(): Promise<void> {
  try {
    await LogService.OpenLogDir()
    message.value = '已打开日志目录'
  } catch (err) {
    setError(err, 'logs.open', '打开日志目录失败')
  }
}

async function copyDiagnostics(): Promise<void> {
  try {
    const text = await LogService.Diagnostics()
    if (!text) {
      message.value = '暂无诊断信息'
      return
    }
    await Clipboard.SetText(text)
    message.value = '诊断信息已复制'
  } catch (err) {
    setError(err, 'logs.diagnostics', '复制诊断信息失败')
  }
}

function isServerFolder(id: string): boolean {
  return serverFolders.value.some((folder) => folder.id === id)
}

const localFolders = computed(() => folders.value.filter((folder) => folder.local))
// Folders() 同时返回虚拟行（'' / uncategorized），云端树只取真实文件夹。
const serverFolders = computed(() =>
  folders.value.filter((folder) => !folder.local && folder.id && folder.id !== UNCATEGORIZED_FOLDER),
)

function isLocalFolder(id: string): boolean {
  return localFolders.value.some((folder) => folder.id === id)
}

const collapsedFolders = ref<Set<string>>(new Set())
const collapsedIds = computed(() => [...collapsedFolders.value])

function folderRow(id: string, name: string, sort: number): FolderRow {
  const found = folders.value.find((folder) => folder.id === id)
  return {
    id,
    parentId: found?.parentId ?? '',
    name,
    sort,
    noteCount: found?.noteCount ?? -1,
    createTime: found?.createTime ?? 0,
    updatedAt: found?.updatedAt ?? 0,
    local: found?.local ?? false,
  }
}

interface FolderCounts {
  total: number
  byFolder: Map<string, number>
}

function countNotes(localOnly: boolean): FolderCounts {
  const counts: FolderCounts = { total: 0, byFolder: new Map() }
  for (const note of quickNotes.value) {
    if (note.kind !== 'NOTE' || note.localOnly !== localOnly) {
      continue
    }
    counts.total += 1
    const key = note.folderId || ''
    counts.byFolder.set(key, (counts.byFolder.get(key) ?? 0) + 1)
  }
  return counts
}

const localFolderCounts = computed(() => countNotes(true))
const serverFolderCounts = computed(() => countNotes(false))

/** 按父级分组（父级缺失按根处理），保持 Go 返回的 sort / createTime 顺序。 */
function groupByParent(list: FolderRow[]): Map<string, FolderRow[]> {
  const ids = new Set(list.map((folder) => folder.id))
  const grouped = new Map<string, FolderRow[]>()
  for (const folder of list) {
    const parentId = folder.parentId && ids.has(folder.parentId) ? folder.parentId : ''
    const siblings = grouped.get(parentId) ?? []
    siblings.push(folder)
    grouped.set(parentId, siblings)
  }
  return grouped
}

/** 平铺成带 depth 的树；collapsed 中的文件夹不展开子级。 */
function flattenFolders(list: FolderRow[], collapsed: Set<string> = new Set()): FolderNode[] {
  const children = groupByParent(list)
  const out: FolderNode[] = []
  const walk = (parentId: string, depth: number, parentPath: string): void => {
    for (const folder of children.get(parentId) ?? []) {
      const path = parentPath ? `${parentPath} / ${folder.name}` : folder.name
      out.push({ ...folder, depth, hasChildren: (children.get(folder.id)?.length ?? 0) > 0, path })
      if (!collapsed.has(folder.id)) {
        walk(folder.id, depth + 1, path)
      }
    }
  }
  walk('', 1, '')
  return out
}

const localTree = computed(() => flattenFolders(localFolders.value, collapsedFolders.value))
const serverTree = computed(() => flattenFolders(serverFolders.value, collapsedFolders.value))

const folderPathById = computed(() => {
  const byId = new Map(folders.value.map((folder) => [folder.id, folder]))
  const paths = new Map<string, string>()
  const resolve = (id: string, guard = 0): string => {
    const cached = paths.get(id)
    if (cached !== undefined) {
      return cached
    }
    const folder = byId.get(id)
    if (!folder || guard > 5) {
      return ''
    }
    const parent = folder.parentId ? resolve(folder.parentId, guard + 1) : ''
    const value = parent ? `${parent} / ${folder.name}` : folder.name
    paths.set(id, value)
    return value
  }
  for (const folder of folders.value) {
    resolve(folder.id)
  }
  return paths
})

function folderPath(id: string): string {
  if (!id || id === UNCATEGORIZED_FOLDER) {
    return '未分类'
  }
  return folderPathById.value.get(id) ?? '未分类'
}

const displayFolders = computed<FolderNode[]>(() => {
  if (sourceFilter.value === 'file') {
    return []
  }
  // 文章没有文件夹语义：类型为文章时不展示文件夹列。
  if (kind.value === 'POST') {
    return []
  }
  const all = folderRow('', '全部笔记', -102)
  const uncategorized = folderRow(UNCATEGORIZED_FOLDER, '未分类', -101)
  if (sourceFilter.value === 'local') {
    const counts = localFolderCounts.value
    return [
      { ...all, depth: 1, hasChildren: false, path: all.name, noteCount: counts.total },
      { ...uncategorized, depth: 1, hasChildren: false, path: uncategorized.name, noteCount: counts.byFolder.get('') ?? 0 },
      ...localTree.value,
    ]
  }
  const counts = serverFolderCounts.value
  return [
    { ...all, depth: 1, hasChildren: false, path: all.name, noteCount: counts.total },
    { ...uncategorized, depth: 1, hasChildren: false, path: uncategorized.name, noteCount: counts.byFolder.get('') ?? 0 },
    ...serverTree.value,
  ]
})

const localFolderOptions = computed<FolderOption[]>(() =>
  flattenFolders(localFolders.value).map((folder) => ({ id: folder.id, label: folderPath(folder.id) })),
)
const serverFolderOptions = computed<FolderOption[]>(() =>
  flattenFolders(serverFolders.value).map((folder) => ({ id: folder.id, label: folderPath(folder.id) })),
)

const folderOptions = computed(() => {
  const tab = activeTab.value
  if (!tab || !tab.note || tab.note.kind === 'POST') {
    return []
  }
  if (tab.note.localOnly) {
    return [{ id: '', name: '未分类' }, ...localFolderOptions.value.map((folder) => ({ id: folder.id, name: folder.label }))]
  }
  return [{ id: '', name: '未分类' }, ...serverFolderOptions.value.map((folder) => ({ id: folder.id, name: folder.label }))]
})

function toggleFolder(id: string): void {
  const next = new Set(collapsedFolders.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  collapsedFolders.value = next
}

function scopeIdForNote(note: NoteRow): string {
  return note.localOnly ? 'local' : (session.value?.scopeId ?? 'local')
}

function noteItem(note: NoteRow): LibraryItem {
  const local = note.localOnly
  return {
    key: tabKeyForNote(note, scopeIdForNote(note)),
    source: local ? 'local' : 'server',
    title: note.title || '未命名',
    subtitle: local
      ? `本地 · ${folderPath(note.folderId)}`
      : note.kind === 'POST'
        ? '文章'
        : folderPath(note.folderId),
    updatedAt: note.updatedAt,
    note,
    pinned: note.pinned,
    conflict: note.conflict,
    dirty: note.dirty,
    localOnly: local,
  }
}

function fileItem(file: RecentFile): LibraryItem {
  return {
    key: fileKey(file.path),
    source: 'file',
    title: file.name,
    subtitle: file.path,
    updatedAt: file.openedAt,
    path: file.path,
    recent: file,
    exists: file.exists,
  }
}

const libraryItems = computed<LibraryItem[]>(() => {
  const value = keyword.value.trim().toLowerCase()
  if (sourceFilter.value === 'file') {
    return recent.value
      .filter((file) => !value || `${file.name} ${file.path}`.toLowerCase().includes(value))
      .map(fileItem)
      .sort((a, b) => b.updatedAt - a.updatedAt)
  }
  return notes.value.map(noteItem)
})

const quickItems = computed<QuickItem[]>(() => {
  const opened = new Set(tabs.value.map((tab) => tab.key))
  const noteItems: QuickItem[] = quickNotes.value.map((note) => {
    const key = tabKeyForNote(note, scopeIdForNote(note))
    return {
      key,
      source: note.localOnly ? 'local' : 'server',
      title: note.title || '未命名',
      subtitle: note.localOnly
        ? `本地 · ${folderPath(note.folderId)}`
        : note.kind === 'POST'
          ? '云端 · 文章'
          : `云端 · ${folderPath(note.folderId)}`,
      updatedAt: note.updatedAt,
      note,
      opened: opened.has(key),
    }
  })
  const fileItems: QuickItem[] = recent.value.map((file) => ({
    key: fileKey(file.path),
    source: 'file',
    title: file.name,
    subtitle: file.path,
    updatedAt: file.openedAt,
    path: file.path,
    opened: opened.has(fileKey(file.path)),
  }))
  return [...noteItems, ...fileItems].sort((a, b) => b.updatedAt - a.updatedAt)
})

const startItems = computed(() => quickItems.value.slice(0, 10))

const quickActions = computed<QuickAction[]>(() => [
  { id: 'new-local', title: '新建本地笔记', hint: 'Ctrl N' },
  { id: 'open-file', title: '打开 Markdown 文件…', hint: 'Ctrl O' },
  { id: 'toggle-sidebar', title: sidebarOpen.value ? '收起资料库' : '展开资料库', hint: 'Ctrl B' },
  ...(!session.value ? [{ id: 'login' as const, title: '登录', hint: '' }] : []),
])

function findTab(key: string): DocumentTab | null {
  return tabs.value.find((tab) => tab.key === key) ?? null
}

function addTab(tab: DocumentTab): DocumentTab {
  const stored = reactive(tab)
  tabs.value.push(stored)
  return stored
}

function clearSaveTimer(key: string): void {
  const timer = saveTimers.get(key)
  if (timer) {
    window.clearTimeout(timer)
    saveTimers.delete(key)
  }
}

function removeTabs(remove: Set<string>, nextKey?: string): void {
  for (const key of remove) {
    clearSaveTimer(key)
    const tab = tabs.value.find((item) => item.key === key)
    if (tab?.source === 'file' && tab.file?.mediaToken) {
      void MediaService.ReleaseFileTab(tab.file.mediaToken)
    }
  }
  const activeRemoved = remove.has(activeKey.value)
  const index = tabs.value.findIndex((tab) => tab.key === activeKey.value)
  tabs.value = tabs.value.filter((tab) => !remove.has(tab.key))
  if (!tabs.value.length) {
    activeKey.value = ''
  } else if (activeRemoved) {
    activeKey.value = nextKey && findTab(nextKey) ? nextKey : tabs.value[Math.min(index, tabs.value.length - 1)].key
    const tab = activeTab.value
    if (tab && !tab.loaded && !tab.loading) {
      void loadTab(tab)
    }
  }
  persistTabs()
}

function setTabKey(tab: DocumentTab, key: string): void {
  if (tab.key === key) {
    return
  }
  const collision = tabs.value.find((item) => item.key === key && item !== tab)
  const collisionActive = !!collision && activeKey.value === collision.key
  if (collision) {
    removeTabs(new Set([collision.key]), key)
  }
  const wasActive = activeKey.value === tab.key
  const oldKey = tab.key
  clearSaveTimer(oldKey)
  tab.key = key
  if (wasActive || collisionActive) {
    activeKey.value = key
  }
  persistTabs()
}

const saveTimers = new Map<string, number>()

function scheduleSave(tab: DocumentTab): void {
  if (tab.source === 'file') {
    return
  }
  clearSaveTimer(tab.key)
  saveTimers.set(
    tab.key,
    window.setTimeout(() => {
      saveTimers.delete(tab.key)
      void saveNote(tab, { silent: true })
    }, 800),
  )
}

function updateNoteRow(note: NoteRow): void {
  const index = notes.value.findIndex((item) => item.id === note.id)
  if (index >= 0) {
    notes.value[index] = note
  }
  const quickIndex = quickNotes.value.findIndex((item) => item.id === note.id)
  if (quickIndex >= 0) {
    quickNotes.value[quickIndex] = note
  }
}

function fileMeta(file: LocalFile): FileMeta {
  return {
    path: file.path,
    name: file.name,
    hash: file.hash,
    mtimeMs: file.mtimeMs,
    size: file.size,
    lineEnding: file.lineEnding,
    bom: file.bom,
    mediaToken: file.mediaToken,
  }
}

function applyFile(tab: DocumentTab, file: LocalFile): void {
  const previousToken = tab.file?.mediaToken
  if (previousToken && previousToken !== file.mediaToken) {
    void MediaService.ReleaseFileTab(previousToken)
  }
  tab.file = fileMeta(file)
  tab.title = file.name
  tab.content = file.content
  tab.loaded = true
  tab.loading = false
  tab.error = undefined
  tab.dirty = false
  tab.saveState = 'idle'
  tab.saveMessage = ''
}

function applyOpenedNote(tab: DocumentTab, note: NoteRow): void {
  const scopeId = tab.note?.scopeId ?? scopeIdForNote(note)
  const meta = noteMetaFromRow(note, scopeId)
  tab.note = meta
  tab.source = meta.localOnly ? 'local' : 'server'
  tab.title = note.title || '未命名'
  tab.content = note.content ?? ''
  tab.loaded = meta.contentLoaded
  tab.loading = false
  tab.dirty = note.dirty
  tab.error = undefined
  if (meta.conflict) {
    tab.saveState = 'conflict'
    tab.dirty = true
  } else if (tab.saveState !== 'saving') {
    tab.saveState = meta.localOnly ? 'saved' : 'idle'
  }
  updateNoteRow(note)
}

function applySavedNote(tab: DocumentTab, note: NoteRow, titleChanged: boolean, folderChanged: boolean): void {
  const scopeId = tab.note?.scopeId ?? scopeIdForNote(note)
  const currentTitle = tab.title
  const currentFolder = tab.note?.folderId ?? ''
  const currentContent = tab.content
  // 服务端保存时会把 local-media 引用改写成 /api/media；只有这种结构性改写才回填编辑器，
  // 避免覆盖用户在保存请求期间继续输入的内容。
  const nextContent =
    note.content && note.content !== currentContent && currentContent.includes('local-media/') && note.content.includes('/api/media/')
      ? note.content
      : currentContent
  const meta = noteMetaFromRow(note, scopeId)
  if (folderChanged) {
    meta.folderId = currentFolder
  } else {
    meta.folderId = note.folderId ?? ''
  }
  tab.note = meta
  tab.title = titleChanged ? currentTitle : note.title || '未命名'
  tab.loaded = true
  tab.error = undefined
  if (nextContent !== currentContent) {
    tab.content = nextContent
  }
  updateNoteRow({
    ...note,
    title: tab.title,
    content: nextContent,
    folderId: folderChanged ? currentFolder : meta.folderId,
  })
}

function createFileTab(file: LocalFile): DocumentTab {
  return {
    key: fileKey(file.path),
    source: 'file',
    title: file.name,
    content: file.content,
    loaded: true,
    loading: false,
    dirty: false,
    saveState: 'idle',
    saveMessage: '',
    scrollTop: 0,
    file: fileMeta(file),
  }
}

function placeholderFileTab(path: string, name?: string): DocumentTab {
  const title = name || path.split(/[\\/]/).pop() || '未命名'
  return {
    key: fileKey(path),
    source: 'file',
    title,
    content: '',
    loaded: false,
    loading: false,
    dirty: false,
    saveState: 'idle',
    saveMessage: '',
    scrollTop: 0,
    file: {
      path,
      name: title,
      hash: '',
      mtimeMs: 0,
      size: 0,
      lineEnding: 'lf',
      bom: false,
    },
  }
}

function canOpenTab(): boolean {
  if (tabs.value.length >= MAX_TABS) {
    error.value = `最多同时打开 ${MAX_TABS} 个标签，请先关闭一个`
    return false
  }
  return true
}

async function openFileTab(file: LocalFile): Promise<DocumentTab | null> {
  const key = fileKey(file.path)
  const existing = findTab(key)
  if (existing) {
    // 已有未保存内容时不要用磁盘版本覆盖；只把标签带到前台。
    if (!existing.dirty) {
      applyFile(existing, file)
    }
    await activateTab(key)
    await loadRecent()
    return existing
  }
  if (!canOpenTab()) {
    return null
  }
  const tab = addTab(createFileTab(file))
  activeKey.value = tab.key
  persistTabs()
  await loadRecent()
  return tab
}

async function openFileDialog(asImport = false): Promise<void> {
  error.value = ''
  try {
    const file = await LocalFileService.OpenDialog()
    if (!file) {
      return
    }
    const tab = await openFileTab(file)
    if (!tab) {
      return
    }
    if (asImport) {
      openImportDialog(tab)
    }
  } catch (err) {
    setError(err, 'file.open', '打开文件失败')
  }
}

async function openPath(path: string): Promise<void> {
  error.value = ''
  try {
    const file = await LocalFileService.OpenPath(path)
    if (!file) {
      return
    }
    await openFileTab(file)
  } catch (err) {
    setError(err, 'file.open', '打开文件失败')
  }
}

async function openRecent(file: RecentFile): Promise<void> {
  if (!file.exists) {
    error.value = `文件不存在：${file.path}`
    return
  }
  await openPath(file.path)
}

async function openDropped(paths: string[]): Promise<void> {
  const markdown = paths.filter((path) => /\.(md|markdown)$/i.test(path))
  if (!markdown.length) {
    if (paths.length) {
      error.value = '只支持打开 .md / .markdown 文件'
    }
    return
  }
  if (paths.length > 1 && markdown.length !== paths.length) {
    message.value = '已忽略非 Markdown 文件'
  }
  for (const path of markdown) {
    if (tabs.value.length >= MAX_TABS) {
      error.value = `最多同时打开 ${MAX_TABS} 个标签，请先关闭一个`
      break
    }
    await openPath(path)
  }
}

async function loadFileTab(tab: DocumentTab): Promise<void> {
  if (!tab.file || tab.loading) {
    return
  }
  if (tab.loaded) {
    return
  }
  tab.loading = true
  tab.error = undefined
  try {
    const file = await LocalFileService.OpenPath(tab.file.path)
    if (file) {
      applyFile(tab, file)
      await loadRecent()
    }
  } catch (err) {
    tab.error = tabErrorText(err, 'file.read')
  } finally {
    tab.loading = false
  }
}

async function loadNoteTab(tab: DocumentTab): Promise<void> {
  if (!tab.note || tab.loading) {
    return
  }
  tab.loading = true
  tab.error = undefined
  try {
    const note = await NotesService.Open(tab.note.id)
    if (note) {
      applyOpenedNote(tab, note)
    }
  } catch (err) {
    if (tab.note?.contentLoaded) {
      tab.saveMessage = tabErrorText(err, 'notes.open')
    } else {
      tab.error = tabErrorText(err, 'notes.open')
    }
  } finally {
    tab.loading = false
  }
}

async function loadTab(tab: DocumentTab): Promise<void> {
  if (tab.source === 'file') {
    await loadFileTab(tab)
  } else if (!tab.loaded) {
    await loadNoteTab(tab)
  }
}

async function openNote(note: NoteRow): Promise<void> {
  if (!note.localOnly && !session.value) {
    error.value = '请先登录后再打开云端笔记'
    return
  }
  if (opening.value) {
    return
  }
  const key = tabKeyForNote(note, scopeIdForNote(note))
  const existing = findTab(key)
  if (existing) {
    await activateTab(key)
    return
  }
  if (!canOpenTab()) {
    return
  }
  opening.value = true
  error.value = ''
  try {
    const tab = addTab(tabFromNote(note, scopeIdForNote(note)))
    if (!tab.note?.contentLoaded) {
      tab.loaded = false
      tab.error = '该笔记尚未缓存，联网后打开'
    }
    activeKey.value = tab.key
    persistTabs()
    try {
      const opened = await NotesService.Open(note.id)
      if (opened) {
        applyOpenedNote(tab, opened)
      }
    } catch (err) {
      if (!tab.loaded) {
        tab.error = tabErrorText(err, 'notes.open')
      } else {
        tab.saveMessage = tabErrorText(err, 'notes.open')
      }
    }
  } catch (err) {
    setError(err, 'notes.open', '打开笔记失败')
  } finally {
    opening.value = false
  }
}

async function flushActiveNote(): Promise<void> {
  const tab = activeTab.value
  if (tab && tab.source !== 'file' && tab.dirty && tab.loaded) {
    await saveNote(tab, { silent: true })
  }
}

async function activateTab(key: string): Promise<void> {
  if (key === activeKey.value) {
    return
  }
  await flushActiveNote()
  activeKey.value = key
  diffOpen.value = false
  listMenu.value = null
  persistTabs()
  const tab = findTab(key)
  if (tab && !tab.loaded && !tab.loading) {
    await loadTab(tab)
  }
}

async function newNote(target: 'local' | 'server' = 'local'): Promise<void> {
  if (creatingNote.value) {
    return
  }
  const serverTarget = target === 'server' && !!session.value
  const folder = serverTarget
    ? sourceFilter.value === 'server' && isServerFolder(folderId.value)
      ? folderId.value
      : ''
    : sourceFilter.value === 'local' && isLocalFolder(folderId.value)
      ? folderId.value
      : ''
  if (!serverTarget) {
    const current = activeTab.value
    // 复用空白笔记必须要求目标文件夹一致：切换文件夹后新建要落到当前文件夹。
    if (
      current?.source === 'local' &&
      !current.dirty &&
      !current.content.trim() &&
      current.title === '未命名' &&
      (current.note?.folderId ?? '') === folder
    ) {
      message.value = '当前已是空白笔记，直接开始写作即可'
      return
    }
  }
  if (!canOpenTab()) {
    return
  }
  creatingNote.value = true
  error.value = ''
  try {
    const result = await NotesService.Create({
      kind: serverTarget ? kind.value : 'NOTE',
      title: '未命名',
      content: '',
      folderId: folder,
      target: serverTarget ? 'server' : 'local',
    })
    if (!result?.note) {
      showError(result?.message ?? '创建失败', 'notes.create')
      return
    }
    const tab = addTab(tabFromNote(result.note, scopeIdForNote(result.note)))
    tab.loaded = true
    tab.dirty = result.state !== 'synced'
    tab.saveState = result.state === 'synced' ? (tab.note?.localOnly ? 'saved' : 'synced') : result.state === 'offline' ? 'offline' : 'error'
    tab.saveMessage = result.state === 'synced' ? '' : result.message
    activeKey.value = tab.key
    persistTabs()
    await refreshAfterMutation()
    message.value = result.message
  } catch (err) {
    setError(err, 'notes.create', '新建笔记失败')
  } finally {
    creatingNote.value = false
  }
}

async function saveNote(tab: DocumentTab, options: { silent?: boolean } = {}): Promise<boolean> {
  if (tab.source === 'file' || !tab.note || !tab.loaded) {
    return false
  }
  const title = tab.title.trim()
  if (!title) {
    if (!options.silent) {
      tab.saveState = 'error'
      tab.saveMessage = '标题不能为空'
    }
    return false
  }
  const snapshot = {
    title,
    content: tab.content,
    folderId: tab.note.folderId ?? '',
  }
  clearSaveTimer(tab.key)
  tab.saveState = 'saving'
  tab.saveMessage = ''
  try {
    const result = await NotesService.Save({
      id: tab.note.id,
      title,
      content: snapshot.content,
      folderId: snapshot.folderId,
    })
    if (!result) {
      tab.saveState = 'error'
      tab.saveMessage = '保存失败'
      tab.dirty = true
      return false
    }
    const titleChanged = tab.title.trim() !== snapshot.title
    const contentChanged = tab.content !== snapshot.content
    const folderChanged = (tab.note?.folderId ?? '') !== snapshot.folderId
    if (result.note) {
      applySavedNote(tab, result.note, titleChanged, folderChanged)
    }
    const changed = titleChanged || contentChanged || folderChanged
    switch (result.state) {
      case 'synced':
        tab.saveState = tab.note?.localOnly ? 'saved' : 'synced'
        tab.saveMessage = ''
        tab.remoteSnapshot = null
        tab.remoteTitle = ''
        tab.remoteVersion = tab.note?.remoteVersion ?? 0
        tab.dirty = changed
        break
      case 'conflict':
        tab.saveState = 'conflict'
        tab.dirty = true
        tab.saveMessage = result.message
        tab.remoteSnapshot = result.remote ?? null
        tab.remoteTitle = result.remoteTitle
        tab.remoteVersion = result.remoteVersion
        break
      case 'offline':
        tab.saveState = 'offline'
        tab.dirty = true
        tab.saveMessage = result.message
        break
      default:
        tab.saveState = 'error'
        tab.dirty = true
        tab.saveMessage = result.message
    }
    if (changed) {
      scheduleSave(tab)
    }
    if (folderChanged) {
      void reloadFolders()
      void loadList()
    }
    if (session.value) {
      void refreshPending()
    }
    return result.state !== 'error'
  } catch (err) {
    tab.saveState = 'error'
    tab.dirty = true
    tab.saveMessage = tabErrorText(err, 'notes.save')
    return false
  }
}

async function saveFile(tab: DocumentTab, force = false): Promise<boolean> {
  if (tab.source !== 'file' || !tab.file || !tab.loaded) {
    return false
  }
  tab.saveState = 'saving'
  tab.saveMessage = ''
  try {
    const result = await LocalFileService.Save({
      path: tab.file.path,
      content: tab.content,
      expectedHash: tab.file.hash,
      force,
    })
    if (!result) {
      tab.saveState = 'error'
      tab.saveMessage = '保存失败'
      return false
    }
    switch (result.state) {
      case 'saved':
        tab.file = {
          ...tab.file,
          path: result.path,
          name: result.name,
          hash: result.hash,
          mtimeMs: result.mtimeMs,
          size: result.size,
          lineEnding: result.lineEnding,
        }
        tab.title = result.name
        tab.dirty = false
        tab.saveState = 'saved'
        tab.saveMessage = ''
        await loadRecent()
        return true
      case 'modified':
        tab.saveState = 'modified'
        tab.saveMessage = result.message
        return false
      case 'missing':
        tab.saveState = 'missing'
        tab.saveMessage = result.message
        return false
      default:
        tab.saveState = 'error'
        tab.saveMessage = result.message || '保存失败'
        return false
    }
  } catch (err) {
    tab.saveState = 'error'
    tab.saveMessage = tabErrorText(err, 'file.save')
    return false
  }
}

async function saveFileAs(tab: DocumentTab): Promise<void> {
  if (tab.source !== 'file' || !tab.file) {
    return
  }
  try {
    const file = await LocalFileService.SaveAs({
      defaultName: tab.file.name,
      content: tab.content,
      sourcePath: tab.file.path,
    })
    if (!file) {
      return
    }
    const oldKey = tab.key
    applyFile(tab, file)
    tab.saveState = 'saved'
    tab.dirty = false
    setTabKey(tab, fileKey(file.path))
    clearSaveTimer(oldKey)
    await loadRecent()
    message.value = `已另存为 ${file.path}`
  } catch (err) {
    tab.saveState = 'error'
    tab.saveMessage = tabErrorText(err, 'file.save-as')
  }
}

function ensureNoteTitle(tab: DocumentTab): void {
  if (tab.source !== 'file' && !tab.title.trim()) {
    tab.title = '未命名'
  }
}

async function saveActive(): Promise<void> {
  const tab = activeTab.value
  if (!tab) {
    return
  }
  ensureNoteTitle(tab)
  if (tab.source === 'file') {
    await saveFile(tab)
  } else {
    await saveNote(tab)
  }
}

function updateTabContent(tab: DocumentTab, value: string): void {
  if (value === tab.content) {
    return
  }
  tab.content = value
  tab.dirty = true
  if (tab.saveState !== 'saving' && tab.saveState !== 'conflict') {
    tab.saveState = 'idle'
  }
  scheduleSave(tab)
}

function updateTabTitle(tab: DocumentTab, value: string): void {
  if (tab.source === 'file' || value === tab.title) {
    return
  }
  tab.title = value
  tab.dirty = true
  if (tab.saveState !== 'saving' && tab.saveState !== 'conflict') {
    tab.saveState = 'idle'
  }
  scheduleSave(tab)
}

function updateTabFolder(tab: DocumentTab, value: string): void {
  if (!tab.note || tab.note.folderId === value) {
    return
  }
  tab.note.folderId = value
  tab.dirty = true
  if (tab.saveState !== 'saving' && tab.saveState !== 'conflict') {
    tab.saveState = 'idle'
  }
  scheduleSave(tab)
}

function updateScroll(tab: DocumentTab, top: number): void {
  tab.scrollTop = top
}

async function reloadTab(tab: DocumentTab): Promise<void> {
  error.value = ''
  if (tab.source === 'file') {
    if (tab.dirty && !window.confirm('放弃未保存修改并重新加载？')) {
      return
    }
    try {
      const file = await LocalFileService.Read(tab.file?.path ?? '')
      if (file) {
        applyFile(tab, file)
      }
    } catch (err) {
      tab.error = tabErrorText(err, 'file.read')
    }
    return
  }
  if (tab.dirty && !window.confirm('重新从本地 / 云端读取？未同步的修改会保留在本地库。')) {
    return
  }
  if (tab.note?.contentLoaded) {
    try {
      const note = await NotesService.Open(tab.note.id)
      if (note) {
        applyOpenedNote(tab, note)
      }
    } catch (err) {
      tab.saveMessage = tabErrorText(err, 'notes.open')
    }
    return
  }
  tab.loaded = false
  await loadNoteTab(tab)
}

function openImportDialog(tab: DocumentTab | null = activeTab.value): void {
  if (!tab || tab.source !== 'file' || !tab.file) {
    return
  }
  importSourceKey.value = tab.key
  importTitle.value = tab.file.name.replace(/\.(md|markdown)$/i, '')
  importOpen.value = true
}

async function confirmImport(payload: { title: string; target: 'local' | 'server'; folderId: string }): Promise<void> {
  const source = findTab(importSourceKey.value)
  if (!source || source.source !== 'file' || importing.value) {
    importOpen.value = false
    return
  }
  importing.value = true
  error.value = ''
  try {
    const result = await NotesService.ImportFile({
      title: payload.title,
      content: source.content,
      target: payload.target,
      folderId: payload.folderId,
      sourcePath: source.file?.path ?? '',
    })
    if (!result?.note) {
      showError(result?.message ?? '纳入管理失败', 'notes.import')
      return
    }
    const index = tabs.value.findIndex((tab) => tab.key === source.key)
    const noteTab = addTab(tabFromNote(result.note, scopeIdForNote(result.note)))
    noteTab.loaded = true
    noteTab.dirty = result.state !== 'synced'
    noteTab.saveState =
      result.state === 'synced' ? (noteTab.note?.localOnly ? 'saved' : 'synced') : result.state === 'offline' ? 'offline' : 'error'
    noteTab.saveMessage = result.state === 'synced' ? '' : result.message
    removeTabs(new Set([source.key]), noteTab.key)
    if (index >= 0) {
      tabs.value.splice(index, 0, tabs.value.splice(tabs.value.indexOf(noteTab), 1)[0])
    }
    activeKey.value = noteTab.key
    importOpen.value = false
    kind.value = 'NOTE'
    folderId.value = ''
    keyword.value = ''
    sourceFilter.value = noteTab.source === 'local' ? 'local' : 'server'
    persistTabs()
    await refreshAfterMutation()
    message.value = result.message
  } catch (err) {
    setError(err, 'notes.import', '纳入管理失败')
  } finally {
    importing.value = false
  }
}

function startPromote(tab: DocumentTab | null = activeTab.value): void {
  if (!tab?.note || !tab.note.localOnly || !session.value) {
    return
  }
  promoteTab.value = tab
  promoteNoteRow.value = null
  promoteFolderId.value = ''
  promoteOpen.value = true
}

function startPromoteNote(note: NoteRow): void {
  if (!note.localOnly || !session.value) {
    return
  }
  promoteTab.value = findTab(tabKeyForNote(note, 'local'))
  promoteNoteRow.value = note
  promoteFolderId.value = ''
  promoteOpen.value = true
}

async function confirmPromote(): Promise<void> {
  const id = promoteTab.value?.note?.id ?? promoteNoteRow.value?.id
  if (!id || promoting.value) {
    return
  }
  promoting.value = true
  error.value = ''
  try {
    const result = await NotesService.PromoteLocal(id, promoteFolderId.value)
    if (!result?.note) {
      showError(result?.message ?? '同步到云端失败', 'notes.promote')
      return
    }
    const tab = promoteTab.value
    if (tab) {
      const previousKey = tab.key
      tab.note = noteMetaFromRow(result.note, session.value?.scopeId ?? 'local')
      tab.source = tab.note.localOnly ? 'local' : 'server'
      tab.title = result.note.title || '未命名'
      tab.content = result.note.content ?? tab.content
      tab.loaded = true
      tab.dirty = result.state !== 'synced'
      tab.saveState = result.state === 'synced' ? (tab.note.localOnly ? 'saved' : 'synced') : 'offline'
      tab.saveMessage = result.state === 'synced' ? '' : result.message
      const key =
        tab.note.localOnly || !session.value
          ? noteKey('local', 'local', result.note.id)
          : noteKey('server', session.value.scopeId, result.note.id)
      setTabKey(tab, key)
      clearSaveTimer(previousKey)
    }
    promoteOpen.value = false
    if (result.note && !result.note.localOnly) {
      folderId.value = ''
      sourceFilter.value = 'server'
    }
    await refreshAfterMutation()
    message.value = result.message
  } catch (err) {
    setError(err, 'notes.promote', '同步到云端失败')
  } finally {
    promoting.value = false
  }
}

async function exportNote(target: NoteRow | DocumentTab): Promise<void> {
  if (exporting.value) {
    return
  }
  listMenu.value = null
  exporting.value = true
  error.value = ''
  try {
    let title = ''
    let content = ''
    if ('source' in target) {
      if (!target.loaded) {
        await loadTab(target)
      }
      if (!target.loaded) {
        error.value = target.error || '文档尚未加载，无法导出'
        return
      }
      title = target.title
      content = target.content
    } else {
      const tab = findTab(tabKeyForNote(target, scopeIdForNote(target)))
      if (tab?.loaded) {
        title = tab.title
        content = tab.content
      } else {
        const opened = await NotesService.Open(target.id)
        if (!opened) {
          return
        }
        title = opened.title
        content = opened.content
      }
    }
    const result = await LocalFileService.ExportMarkdown({ defaultName: title, content })
    if (result?.path) {
      message.value = `已导出到 ${result.path}`
    }
  } catch (err) {
    setError(err, 'notes.export', '导出失败')
  } finally {
    exporting.value = false
  }
}

async function deleteNoteTab(tab: DocumentTab): Promise<void> {
  if (!tab.note) {
    return
  }
  error.value = ''
  try {
    const result = await NotesService.Delete(tab.note.id)
    if (result?.state === 'error') {
      showError(result.message, 'notes.delete')
      return
    }
    removeTabs(new Set([tab.key]))
    await refreshAfterMutation()
    message.value = result?.message ?? '已删除'
  } catch (err) {
    setError(err, 'notes.delete', '删除失败')
  }
}

async function resolveDelete(side: 'local' | 'remote'): Promise<void> {
  const tab = activeTab.value
  if (!tab?.note) {
    return
  }
  try {
    const result = await NotesService.ResolveDelete(tab.note.id, side)
    removeTabs(new Set([tab.key]))
    await refreshAfterMutation()
    message.value = result?.message ?? ''
  } catch (err) {
    setError(err, 'notes.resolve-delete', '处理删除冲突失败')
  }
}

async function resolveConflict(side: 'local' | 'remote'): Promise<void> {
  const tab = activeTab.value
  if (!tab?.note) {
    return
  }
  tab.saveState = 'saving'
  try {
    const result = await NotesService.Resolve(tab.note.id, side)
    if (result?.note) {
      applyOpenedNote(tab, result.note)
      tab.dirty = result.state !== 'synced'
      tab.saveState = result.state === 'synced' ? 'synced' : result.state === 'offline' ? 'offline' : 'error'
      tab.remoteSnapshot = null
    }
    diffOpen.value = false
    await refreshAfterMutation()
  } catch (err) {
    tab.saveState = 'error'
    tab.saveMessage = tabErrorText(err, 'notes.resolve')
  }
}

async function openAdmin(tab: DocumentTab): Promise<void> {
  if (!tab.note || tab.note.kind !== 'POST') {
    return
  }
  try {
    await SystemService.OpenAdmin(`/posts/${tab.note.id}`)
  } catch (err) {
    setError(err, 'system.open-admin', '打开管理端失败')
  }
}

function onDocumentAction(action: DocumentAction): void {
  error.value = ''
  const tab = activeTab.value
  if (!tab) {
    return
  }
  switch (action) {
    case 'save':
      void (tab.source === 'file' ? saveFile(tab) : saveNote(tab))
      break
    case 'force-save':
      if (tab.source === 'file') {
        void saveFile(tab, true)
      }
      break
    case 'save-as':
      void saveFileAs(tab)
      break
    case 'import':
      openImportDialog(tab)
      break
    case 'promote':
      startPromote(tab)
      break
    case 'export':
      void exportNote(tab)
      break
    case 'delete':
      void deleteNoteTab(tab)
      break
    case 'close':
      requestCloseTab(tab)
      break
    case 'reload':
      void reloadTab(tab)
      break
    case 'diff':
      diffOpen.value = !!tab.remoteSnapshot
      break
    case 'open-admin':
      void openAdmin(tab)
      break
    case 'resolve-local':
      void resolveConflict('local')
      break
    case 'resolve-remote':
      void resolveConflict('remote')
      break
    case 'resolve-delete-local':
      void resolveDelete('local')
      break
    case 'resolve-delete-remote':
      void resolveDelete('remote')
      break
  }
}

function requestCloseTab(tab: DocumentTab): void {
  if (tab.source === 'file' && tab.dirty) {
    closeDialog.value = { mode: 'tab', tab, files: [tab] }
    return
  }
  void finalizeCloseTab(tab)
}

async function finalizeCloseTab(tab: DocumentTab): Promise<void> {
  ensureNoteTitle(tab)
  if (tab.source !== 'file' && tab.dirty && tab.loaded) {
    await saveNote(tab, { silent: true })
  }
  removeTabs(new Set([tab.key]))
}

async function requestCloseOthers(tab: DocumentTab): Promise<void> {
  const others = tabs.value.filter((item) => item.key !== tab.key)
  await flushNotes(others)
  const dirty = others.filter((item) => item.source === 'file' && item.dirty)
  if (dirty.length) {
    closeDialog.value = { mode: 'others', tab, files: dirty }
    return
  }
  removeTabs(new Set(others.map((item) => item.key)), tab.key)
}

async function requestCloseRight(tab: DocumentTab): Promise<void> {
  const index = tabs.value.findIndex((item) => item.key === tab.key)
  if (index < 0) {
    return
  }
  const right = tabs.value.slice(index + 1)
  await flushNotes(right)
  const dirty = right.filter((item) => item.source === 'file' && item.dirty)
  if (dirty.length) {
    closeDialog.value = { mode: 'right', tab, files: dirty }
    return
  }
  removeTabs(new Set(right.map((item) => item.key)), tab.key)
}

async function requestCloseAll(): Promise<void> {
  await flushNotes()
  const dirty = tabs.value.filter((item) => item.source === 'file' && item.dirty)
  if (dirty.length) {
    closeDialog.value = { mode: 'all', files: dirty }
    return
  }
  removeTabs(new Set(tabs.value.map((item) => item.key)))
}

async function flushNotes(target: DocumentTab[] = tabs.value): Promise<void> {
  for (const tab of target) {
    ensureNoteTitle(tab)
    if (tab.source !== 'file' && tab.dirty && tab.loaded) {
      await saveNote(tab, { silent: true })
    }
  }
}

async function handleCloseRequest(): Promise<void> {
  await flushNotes()
  const dirty = tabs.value.filter((tab) => tab.source === 'file' && tab.dirty)
  if (dirty.length) {
    closeDialog.value = { mode: 'app', files: dirty }
    return
  }
  await persistTabsNow()
  await LocalFileService.ConfirmClose()
}

async function confirmCloseDialog(action: 'save' | 'discard' | 'cancel' | 'one-by-one'): Promise<void> {
  const state = closeDialog.value
  if (!state) {
    return
  }
  if (action === 'cancel') {
    closeDialog.value = null
    return
  }
  if (action === 'one-by-one') {
    closeDialog.value = null
    const first = state.files[0]
    if (first) {
      await activateTab(first.key)
    }
    return
  }
  if (action === 'save') {
    for (const tab of state.files) {
      const ok = await saveFile(tab)
      if (!ok) {
        closeDialog.value = null
        await activateTab(tab.key)
        return
      }
    }
  } else {
    for (const tab of state.files) {
      tab.dirty = false
    }
  }
  closeDialog.value = null
  switch (state.mode) {
    case 'tab':
      if (state.tab) {
        await finalizeCloseTab(state.tab)
      }
      break
    case 'others': {
      const others = tabs.value.filter((item) => item.key !== state.tab?.key)
      await flushNotes(others)
      removeTabs(new Set(others.map((item) => item.key)), state.tab?.key)
      break
    }
    case 'right': {
      const index = tabs.value.findIndex((item) => item.key === state.tab?.key)
      if (index >= 0) {
        const right = tabs.value.slice(index + 1)
        await flushNotes(right)
        removeTabs(new Set(right.map((item) => item.key)), state.tab?.key)
      }
      break
    }
    case 'all':
      await flushNotes()
      removeTabs(new Set(tabs.value.map((item) => item.key)))
      break
    case 'app':
      await flushNotes()
      await persistTabsNow()
      await LocalFileService.ConfirmClose()
      break
  }
}

async function loadRecent(): Promise<void> {
  try {
    recent.value = (await LocalFileService.Recent()) ?? []
  } catch (err) {
    setError(err, 'files.recent', '读取最近文件失败')
  }
}

async function removeRecent(path: string): Promise<void> {
  try {
    await LocalFileService.RemoveRecent(path)
    await loadRecent()
  } catch (err) {
    setError(err, 'files.recent-remove', '移除最近文件失败')
  }
}

async function clearRecent(): Promise<void> {
  try {
    await LocalFileService.ClearRecent()
    recent.value = []
  } catch (err) {
    setError(err, 'files.recent-clear', '清空最近文件失败')
  }
}

async function reloadFolders(): Promise<void> {
  try {
    folders.value = (await NotesService.Folders()) ?? []
    const known = new Set(['', UNCATEGORIZED_FOLDER, ...folders.value.map((folder) => folder.id)])
    if (folderId.value && !known.has(folderId.value)) {
      folderId.value = ''
    }
  } catch (err) {
    setError(err, 'folders.list', '读取文件夹失败')
  }
}

async function loadList(): Promise<void> {
  if (sourceFilter.value === 'file') {
    notes.value = []
    return
  }
  try {
    const wantedKind = sourceFilter.value === 'local' ? 'NOTE' : kind.value
    let rows = (await NotesService.List(wantedKind, folderId.value, keyword.value)) ?? []
    if (sourceFilter.value === 'local') {
      rows = rows.filter((note) => note.localOnly)
    } else if (sourceFilter.value === 'server') {
      rows = rows.filter((note) => !note.localOnly)
    }
    notes.value = rows
  } catch (err) {
    setError(err, 'notes.list', '读取笔记列表失败')
  }
}

async function loadQuickIndex(): Promise<void> {
  try {
    quickNotes.value = (await NotesService.List('', '', '')) ?? []
  } catch (err) {
    reportSyncError(err, 'notes.quick')
  }
}

async function refreshPending(): Promise<void> {
  if (!session.value) {
    pendingOutbox.value = 0
    return
  }
  try {
    pendingOutbox.value = (await SyncService.Pending()) ?? 0
  } catch {
    pendingOutbox.value = 0
  }
}

async function refreshAfterMutation(): Promise<void> {
  await Promise.all([reloadFolders(), loadList(), loadQuickIndex(), refreshPending()])
}

async function refreshIndex(manual: boolean): Promise<void> {
  if (!session.value || syncing.value) {
    await refreshAfterMutation()
    return
  }
  syncing.value = true
  syncError.value = ''
  error.value = ''
  errorDetail.value = ''
  errorKind.value = ''
  try {
    const result = await SyncService.Refresh()
    online.value = true
    if (result) {
      pendingOutbox.value = result.pending
      if (manual) {
        message.value = `索引已同步：随手记 ${result.notes} · 文章 ${result.posts} · 文件夹 ${result.folders}`
      }
    }
  } catch (err) {
    reportSyncError(err)
  } finally {
    syncing.value = false
  }
  await refreshAfterMutation()
}

async function retryOutbox(): Promise<void> {
  if (!session.value) {
    return
  }
  try {
    const result = await SyncService.Flush()
    pendingOutbox.value = result?.pending ?? 0
    if ((result?.replayed ?? 0) > 0) {
      message.value = `已重试 ${result?.replayed ?? 0} 项`
      await refreshAfterMutation()
    } else {
      message.value = '没有到期的重试项'
    }
    await refreshPending()
  } catch (err) {
    setError(err, 'sync.flush', '重试待同步项失败')
  }
}

async function createFolder(name: string, parentId = ''): Promise<void> {
  try {
    const target = sourceFilter.value === 'local' ? 'local' : 'server'
    const result = await FoldersService.Create(name, target, parentId)
    if (result?.state !== 'ok') {
      showError(result?.message ?? '创建文件夹失败', 'folders.create')
      return
    }
    if (parentId) {
      const next = new Set(collapsedFolders.value)
      next.delete(parentId)
      collapsedFolders.value = next
    }
    await refreshPending()
    await reloadFolders()
    message.value = result.message || '文件夹已创建'
  } catch (err) {
    setError(err, 'folders.create', '创建文件夹失败')
  }
}

async function renameFolder(folder: FolderRow, name: string): Promise<void> {
  try {
    const result = await FoldersService.Rename(folder.id, name)
    if (result?.state === 'error') {
      showError(result.message, 'folders.rename')
      return
    }
    if (result?.message) {
      message.value = result.message
    }
    await refreshPending()
    await reloadFolders()
    await loadList()
  } catch (err) {
    setError(err, 'folders.rename', '重命名文件夹失败')
  }
}

async function deleteFolder(folder: FolderRow): Promise<void> {
  try {
    const result = await FoldersService.Delete(folder.id)
    if (result?.state === 'error') {
      showError(result.message, 'folders.delete')
      return
    }
    if (folderId.value === folder.id) {
      folderId.value = ''
    }
    for (const tab of tabs.value) {
      if (tab.note?.localOnly && tab.note.folderId === folder.id) {
        tab.note.folderId = ''
      }
    }
    await refreshPending()
    await refreshAfterMutation()
    message.value = result?.message ?? ''
  } catch (err) {
    setError(err, 'folders.delete', '删除文件夹失败')
  }
}

function parseTabs(raw: string): PersistedTabs | null {
  try {
    const value = JSON.parse(raw) as PersistedTabs
    if (!value || !Array.isArray(value.tabs)) {
      return null
    }
    return value
  } catch {
    return null
  }
}

async function restoreTabs(): Promise<void> {
  try {
    const raw = await SystemService.GetUIState('open_tabs')
    if (!raw) {
      return
    }
    const value = parseTabs(raw)
    if (!value) {
      return
    }
    const restored: DocumentTab[] = []
    for (const item of value.tabs.slice(0, MAX_TABS)) {
      if (item.source === 'file' && item.path) {
        restored.push(placeholderFileTab(item.path, item.name))
        continue
      }
      if (item.source === 'local' && item.id) {
        const note = await NotesService.OpenCached(item.id)
        if (note && !note.deleted) {
          const tab = tabFromNote(note, 'local')
          if (!tab.note?.contentLoaded) {
            tab.loaded = false
            tab.error = '本地笔记正文缺失，请重试'
          }
          restored.push(tab)
        }
        continue
      }
      if (item.source === 'server' && item.id && item.scopeId && session.value?.scopeId === item.scopeId) {
        const note = await NotesService.OpenCached(item.id)
        if (note && !note.deleted) {
          const tab = tabFromNote(note, item.scopeId)
          if (!tab.note?.contentLoaded) {
            tab.loaded = false
            tab.error = '该笔记尚未缓存，联网后打开'
          }
          restored.push(tab)
        }
      }
    }
    tabs.value = restored.map((tab) => reactive(tab))
    if (!tabs.value.length) {
      await persistTabsNow()
      return
    }
    const index = Math.min(Math.max(value.active ?? 0, 0), tabs.value.length - 1)
    activeKey.value = tabs.value[index].key
    if (restored.length !== value.tabs.length) {
      await persistTabsNow()
    }
    const active = activeTab.value
    if (active) {
      if (active.source === 'file') {
        await loadFileTab(active)
      } else if (!active.loaded) {
        // 不阻塞首屏：未缓存的服务端笔记留在占位态，用户点重试或切换时再拉取。
      }
    }
  } catch (err) {
    setError(err, 'tabs.restore', '恢复标签失败')
  }
}

async function loadUIState(): Promise<void> {
  try {
    const width = await SystemService.GetUIState('sidebar_width')
    const value = Number(width)
    if (Number.isFinite(value) && value >= 380 && value <= 520) {
      sidebarWidth.value = value
    }
  } catch {
    // UI 状态读取失败不影响主流程
  }
}

function persistPayload(): PersistedTabs {
  return {
    tabs: tabs.value
      .map((tab): PersistedTab | null => {
        if (tab.source === 'file' && tab.file) {
          return { source: 'file', path: tab.file.path, name: tab.file.name }
        }
        if (tab.source === 'local' && tab.note) {
          return { source: 'local', id: tab.note.id }
        }
        if (tab.source === 'server' && tab.note) {
          return { source: 'server', id: tab.note.id, scopeId: tab.note.scopeId }
        }
        return null
      })
      .filter((item): item is PersistedTab => !!item),
    active: Math.max(0, tabs.value.findIndex((tab) => tab.key === activeKey.value)),
  }
}

let persistTimer: number | undefined

function persistTabs(): void {
  window.clearTimeout(persistTimer)
  persistTimer = window.setTimeout(() => void persistTabsNow(), 250)
}

async function persistTabsNow(): Promise<void> {
  window.clearTimeout(persistTimer)
  try {
    await SystemService.SetUIState('open_tabs', JSON.stringify(persistPayload()))
    await SystemService.SetUIState('sidebar_width', String(sidebarWidth.value))
  } catch {
    // 本地 UI 状态写入失败不阻塞编辑
  }
}

async function bootstrap(): Promise<void> {
  try {
    session.value = await AuthService.Current()
  } catch (err) {
    setError(err, 'auth.current', '读取登录状态失败')
  }
  if (session.value) {
    try {
      status.value = await SystemService.Status()
    } catch (err) {
      setError(err, 'system.status', '读取运行状态失败')
    }
  }
  await Promise.all([loadRecent(), loadUIState()])
  await restoreTabs()
  await Promise.all([reloadFolders(), loadList(), loadQuickIndex(), refreshPending()])
  loadingRestore.value = false
  if (session.value) {
    void refreshIndex(false)
  }
  void SystemService.SetTitle(windowTitle.value).catch(() => {})
}

function openLogin(): void {
  loginOpen.value = true
}

function closeLogin(): void {
  loginOpen.value = false
}

async function onLoggedIn(value: Session): Promise<void> {
  loginOpen.value = false
  session.value = value
  try {
    status.value = await SystemService.Status()
  } catch (err) {
    setError(err, 'system.status', '读取运行状态失败')
  }
  message.value = '已登录，云端页签已可用'
  await refreshAfterMutation()
  if (session.value) {
    void refreshIndex(false)
  }
}

async function closeServerTabs(): Promise<void> {
  const serverTabs = tabs.value.filter((tab) => tab.source === 'server')
  if (!serverTabs.length) {
    return
  }
  removeTabs(new Set(serverTabs.map((tab) => tab.key)))
}

async function logout(): Promise<void> {
  if (!session.value) {
    return
  }
  await flushNotes(tabs.value.filter((tab) => tab.source === 'server'))
  try {
    await AuthService.Logout()
  } catch (err) {
    setError(err, 'auth.logout', '退出登录失败')
  }
  session.value = null
  status.value = null
  pendingOutbox.value = 0
  syncError.value = ''
  if (sourceFilter.value === 'server') {
    sourceFilter.value = 'local'
  }
  kind.value = 'NOTE'
  if (isServerFolder(folderId.value)) {
    folderId.value = ''
  }
  keyword.value = ''
  await closeServerTabs()
  await refreshAfterMutation()
  message.value = '已退出登录，回到本地模式'
}

function toggleSidebar(): void {
  sidebarOpen.value = !sidebarOpen.value
}

function setSourceFilter(value: LibraryFilter): void {
  if (value === 'local') {
    kind.value = 'NOTE'
    if (folderId.value && !isLocalFolder(folderId.value)) {
      folderId.value = ''
    }
  } else if (value === 'server') {
    if (folderId.value && !isServerFolder(folderId.value)) {
      folderId.value = ''
    }
  } else {
    kind.value = 'NOTE'
    folderId.value = ''
  }
  sourceFilter.value = value
  if (kind.value === 'POST') {
    folderId.value = ''
  }
}

function setKind(value: Kind): void {
  if (sourceFilter.value === 'local' || sourceFilter.value === 'file') {
    return
  }
  kind.value = value
  if (value === 'POST') {
    folderId.value = ''
  }
}

function selectFolder(id: string): void {
  folderId.value = id
}

async function selectItem(item: LibraryItem): Promise<void> {
  if (!isDesktop.value) {
    sidebarOpen.value = false
  }
  if (item.source === 'file') {
    if (item.recent) {
      await openRecent(item.recent)
    } else if (item.path) {
      await openPath(item.path)
    }
    return
  }
  if (item.note) {
    await openNote(item.note)
  }
}

async function openQuickItem(item: QuickItem): Promise<void> {
  quickOpen.value = false
  if (item.note) {
    await openNote(item.note)
  } else if (item.path) {
    await openPath(item.path)
  }
}

function onQuickAction(id: QuickAction['id']): void {
  quickOpen.value = false
  switch (id) {
    case 'new-local':
      void newNote('local')
      break
    case 'open-file':
      void openFileDialog()
      break
    case 'toggle-sidebar':
      toggleSidebar()
      break
    case 'login':
      openLogin()
      break
  }
}

const EDITABLE_SELECTOR = 'input, textarea, [contenteditable]:not([contenteditable="false"])'

function openListMenu(item: LibraryItem, event: MouseEvent): void {
  event.preventDefault()
  listMenu.value = { x: event.clientX, y: event.clientY, item }
}

const listMenuItems = computed<ContextMenuItem[]>(() => {
  const item = listMenu.value?.item
  if (!item) {
    return []
  }
  if (item.source === 'file') {
    const items: ContextMenuItem[] = [{ key: 'open', label: '打开' }]
    if (item.recent) {
      items.push({ key: 'separator-1', separator: true })
      items.push({ key: 'remove-recent', label: '从最近列表移除', danger: true })
    }
    return items
  }
  const items: ContextMenuItem[] = [{ key: 'open', label: '打开' }]
  if (item.note) {
    items.push({ key: 'export', label: '导出为 Markdown…' })
    if (item.note.localOnly && session.value) {
      items.push({ key: 'promote', label: '同步到云端' })
    }
    items.push({ key: 'separator-1', separator: true })
    items.push({ key: 'delete', label: '删除', danger: true })
  }
  return items
})

async function runListMenu(key: string): Promise<void> {
  const item = listMenu.value?.item
  listMenu.value = null
  if (!item) {
    return
  }
  switch (key) {
    case 'open':
      await selectItem(item)
      break
    case 'export':
      if (item.note) {
        await exportNote(item.note)
      }
      break
    case 'promote':
      if (item.note) {
        startPromoteNote(item.note)
      }
      break
    case 'delete':
      if (item.note) {
        await deleteNoteRow(item.note)
      }
      break
    case 'remove-recent':
      await removeRecent(item.recent?.path ?? '')
      break
  }
}

/** 列表内删除：与标签删除一致，顺手关掉已打开的标签。 */
async function deleteNoteRow(note: NoteRow): Promise<void> {
  error.value = ''
  try {
    const result = await NotesService.Delete(note.id)
    if (result?.state === 'error') {
      showError(result.message, 'notes.delete')
      return
    }
    removeTabs(new Set([tabKeyForNote(note, scopeIdForNote(note))]))
    await refreshAfterMutation()
    message.value = result?.message ?? '已删除'
  } catch (err) {
    setError(err, 'notes.delete', '删除失败')
  }
}

const textMenuItems = computed<ContextMenuItem[]>(() => {
  const state = textMenu.value
  if (!state) {
    return []
  }
  const hasSelection = state.range
    ? !state.range.collapsed
    : Boolean(state.input && state.input.start !== state.input.end)
  return [
    { key: 'undo', label: '撤销', hint: 'Ctrl+Z' },
    { key: 'redo', label: '重做', hint: 'Ctrl+Y' },
    { key: 'separator-1', separator: true },
    { key: 'cut', label: '剪切', hint: 'Ctrl+X', disabled: !hasSelection },
    { key: 'copy', label: '复制', hint: 'Ctrl+C', disabled: !hasSelection },
    { key: 'paste', label: '粘贴', hint: 'Ctrl+V' },
    { key: 'separator-2', separator: true },
    { key: 'select-all', label: '全选', hint: 'Ctrl+A' },
  ]
})

/** 应用内统一接管右键：编辑控件用自定义文本菜单，其余区域不显示浏览器默认菜单。 */
function handleContextMenu(event: MouseEvent): void {
  if (event.defaultPrevented) {
    return
  }
  event.preventDefault()
  const target = event.target instanceof HTMLElement ? event.target : null
  const element = target?.closest<HTMLElement>(EDITABLE_SELECTOR) ?? null
  if (!element) {
    textMenu.value = null
    return
  }
  const state: TextMenuState = { x: event.clientX, y: event.clientY, element }
  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
    state.input = { start: element.selectionStart ?? 0, end: element.selectionEnd ?? 0 }
  } else {
    const selection = window.getSelection()
    if (selection && selection.rangeCount > 0) {
      const range = selection.getRangeAt(0)
      if (element.contains(range.commonAncestorContainer)) {
        state.range = range.cloneRange()
      }
    }
  }
  textMenu.value = state
}

function restoreTextSelection(state: TextMenuState): void {
  const element = state.element
  element.focus()
  if (state.input && (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement)) {
    element.setSelectionRange(state.input.start, state.input.end)
    return
  }
  if (state.range) {
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(state.range)
  }
}

function runTextMenu(key: string): void {
  const state = textMenu.value
  textMenu.value = null
  if (!state) {
    return
  }
  restoreTextSelection(state)
  if (key === 'paste') {
    void pasteInto(state)
    return
  }
  const command = key === 'select-all' ? 'selectAll' : key
  try {
    document.execCommand(command)
  } catch {
    // 浏览器不支持 execCommand 时保持静默，不影响其他操作。
  }
}

/** 粘贴走 Go 侧剪贴板：WebView 不以 navigator.clipboard 读系统剪贴板。 */
async function pasteInto(state: TextMenuState): Promise<void> {
  let text = ''
  try {
    text = await Clipboard.Text()
  } catch {
    return
  }
  if (!text) {
    return
  }
  const element = state.element
  restoreTextSelection(state)
  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
    const start = element.selectionStart ?? state.input?.start ?? element.value.length
    const end = element.selectionEnd ?? state.input?.end ?? start
    element.setRangeText(text, start, end, 'end')
    element.dispatchEvent(new Event('input', { bubbles: true }))
    return
  }
  try {
    document.execCommand('insertText', false, text)
  } catch {
    // 同上：不支持时保持静默。
  }
}

function startResize(event: PointerEvent): void {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = sidebarWidth.value
  const move = (moveEvent: PointerEvent): void => {
    sidebarWidth.value = Math.min(520, Math.max(380, startWidth + moveEvent.clientX - startX))
  }
  const up = (): void => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    void SystemService.SetUIState('sidebar_width', String(sidebarWidth.value)).catch(() => {})
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function lineDiff(before: string, after: string): { type: 'same' | 'add' | 'del'; text: string }[] {
  const a = before.split(/\r?\n/)
  const b = after.split(/\r?\n/)
  const n = a.length
  const m = b.length
  if (n * m > 250000) {
    return [
      ...a.map((text) => ({ type: 'del' as const, text })),
      ...b.map((text) => ({ type: 'add' as const, text })),
    ]
  }
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: { type: 'same' | 'add' | 'del'; text: string }[] = []
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

const diffLines = computed(() => {
  const tab = activeTab.value
  if (!diffOpen.value || !tab?.remoteSnapshot) {
    return []
  }
  return lineDiff(tab.remoteSnapshot.content, tab.content)
})

function cycleTab(step: number): void {
  if (tabs.value.length < 2) {
    return
  }
  const index = tabs.value.findIndex((tab) => tab.key === activeKey.value)
  const next = (index + step + tabs.value.length) % tabs.value.length
  void activateTab(tabs.value[next].key)
}

function isEditorTarget(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && !!target.closest('.vditor-ir, .vditor-wysiwyg, .vditor-sv')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    if (textMenu.value) {
      textMenu.value = null
      return
    }
    if (listMenu.value) {
      listMenu.value = null
      return
    }
    if (quickOpen.value) {
      quickOpen.value = false
      return
    }
    if (!isDesktop.value && sidebarOpen.value) {
      sidebarOpen.value = false
    }
    return
  }
  const mod = event.ctrlKey || event.metaKey
  if (!mod) {
    return
  }
  if (loginOpen.value || importOpen.value || promoteOpen.value || closeDialog.value || diffOpen.value) {
    return
  }
  const key = event.key.toLowerCase()
  const inEditor = isEditorTarget(event.target)
  if (key === 's') {
    event.preventDefault()
    void saveActive()
    return
  }
  if (key === 'o') {
    // 编辑器内 Ctrl+O 是 Vditor 的有序列表；打开文件用顶栏 / 快速打开。
    if (inEditor) {
      return
    }
    event.preventDefault()
    void openFileDialog()
    return
  }
  if (key === 'n') {
    event.preventDefault()
    void newNote('local')
    return
  }
  if (key === 'b') {
    // 编辑器内 Ctrl+B 是加粗；侧栏用顶栏按钮或 Ctrl+Shift+L。
    if (inEditor) {
      return
    }
    event.preventDefault()
    toggleSidebar()
    return
  }
  if (key === 'l' && event.shiftKey) {
    event.preventDefault()
    toggleSidebar()
    return
  }
  if (key === 'k') {
    // 编辑器内 Ctrl+K 是链接；快速打开用顶栏按钮或 Ctrl+Shift+K。
    if (inEditor && !event.shiftKey) {
      return
    }
    event.preventDefault()
    quickOpen.value = !quickOpen.value
    return
  }
  if (key === 'w') {
    event.preventDefault()
    const tab = activeTab.value
    if (tab) {
      requestCloseTab(tab)
    }
    return
  }
  if (key === 'tab') {
    event.preventDefault()
    cycleTab(event.shiftKey ? -1 : 1)
    return
  }
  if (/^[1-9]$/.test(key)) {
    event.preventDefault()
    const index = key === '9' ? tabs.value.length - 1 : Number(key) - 1
    const tab = tabs.value[index]
    if (tab) {
      void activateTab(tab.key)
    }
  }
}

let offDrop: (() => void) | undefined
let offClose: (() => void) | undefined
let searchTimer: number | undefined
let titleTimer: number | undefined

watch(
  [sourceFilter, kind, folderId],
  () => {
    void loadList()
  },
)

watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void loadList(), 250)
})

let messageTimer: number | undefined

watch(message, (value) => {
  window.clearTimeout(messageTimer)
  if (!value) {
    return
  }
  messageTimer = window.setTimeout(() => {
    message.value = ''
  }, 4000)
})

// 错误被清空时同步清掉详情，避免下一次操作弹出旧的技术细节。
watch(error, (value) => {
  if (!value) {
    errorDetail.value = ''
    errorKind.value = ''
    errorOpen.value = false
  }
})

watch(unsaved, (value) => {
  LocalFileService.SetUnsaved(value).catch(() => {})
})

watch(windowTitle, (value) => {
  window.clearTimeout(titleTimer)
  titleTimer = window.setTimeout(() => {
    void SystemService.SetTitle(value).catch(() => {})
  }, 150)
})

onMounted(() => {
  window.addEventListener('keydown', onKeydown, true)
  window.addEventListener('contextmenu', handleContextMenu)
  window.addEventListener('online', updateOnline)
  window.addEventListener('offline', updateOnline)
  LocalFileService.SetUnsaved(false).catch(() => {})
  offDrop = Events.On('app:files-dropped', (event) => {
    const data = event.data as string[] | { filenames?: string[] } | null | undefined
    const filenames = Array.isArray(data) ? data : (data?.filenames ?? [])
    void openDropped(filenames)
  })
  offClose = Events.On('app:close-requested', () => {
    void handleCloseRequest()
  })
  void bootstrap()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown, true)
  window.removeEventListener('contextmenu', handleContextMenu)
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
  window.clearTimeout(searchTimer)
  window.clearTimeout(titleTimer)
  window.clearTimeout(messageTimer)
  for (const timer of saveTimers.values()) {
    window.clearTimeout(timer)
  }
  offDrop?.()
  offClose?.()
})

function updateOnline(): void {
  online.value = navigator.onLine
  if (!online.value) {
    syncError.value = ''
    return
  }
  // 系统恢复联网后主动重试一次；失败会再次把 online 置为 false。
  if (session.value) {
    void refreshIndex(false)
  }
}
</script>

<template>
  <div class="flex h-full flex-col bg-app-bg text-app-fg" data-file-drop-target="true">
    <AppHeader
      :session="session"
      :status="status"
      :syncing="syncing"
      :pending="pendingOutbox"
      :online="online"
      :sidebar-open="sidebarOpen"
      :theme="themeMode"
      @toggle-sidebar="toggleSidebar"
      @quick-open="quickOpen = true"
      @new-local="newNote('local')"
      @new-server="newNote('server')"
      @open-file="openFileDialog()"
      @open-import="openFileDialog(true)"
      @login="openLogin"
      @logout="logout"
      @sync="refreshIndex(true)"
      @retry="retryOutbox"
      @set-theme="setThemeMode"
      @open-logs="openLogs"
      @copy-diagnostics="copyDiagnostics"
    />

    <div class="flex min-h-0 flex-1">
      <template v-if="sidebarOpen">
        <div
          v-if="!isDesktop"
          class="fixed inset-0 z-40 bg-black/40"
          @click="sidebarOpen = false"
        />
        <div
          class="min-h-0 shrink-0"
          :class="!isDesktop ? 'fixed bottom-[26px] left-0 top-11 z-50 shadow-2xl' : 'relative'"
          :style="{ width: `${sidebarWidth}px` }"
        >
          <LibrarySidebar
            :session="session"
            :folders="displayFolders"
            :collapsed-ids="collapsedIds"
            :items="libraryItems"
            :active-key="activeKey"
            :source-filter="sourceFilter"
            :kind="kind"
            :folder-id="folderId"
            :keyword="keyword"
            :online="online"
            :pending="pendingOutbox"
            @update:source-filter="setSourceFilter"
            @update:kind="setKind"
            @update:folder-id="selectFolder"
            @update:keyword="keyword = $event"
            @select="selectItem"
            @new-note="newNote('local')"
            @open-file="openFileDialog()"
            @new-folder="createFolder"
            @toggle-folder="toggleFolder"
            @rename-folder="renameFolder"
            @delete-folder="deleteFolder"
            @remove-recent="removeRecent"
            @clear-recent="clearRecent"
            @retry="retryOutbox"
            @login="openLogin"
            @close="sidebarOpen = false"
            @contextmenu="openListMenu"
          />
          <div
            v-if="isDesktop"
            class="absolute -right-1 bottom-0 top-0 z-10 w-2 cursor-col-resize hover:bg-brand-400/30"
            title="拖动调整资料库宽度"
            @pointerdown="startResize"
          />
        </div>
      </template>

      <main class="flex min-w-0 flex-1 flex-col">
        <TabBar
          v-if="tabs.length"
          :tabs="tabs"
          :active-key="activeKey"
          @select="activateTab"
          @close="requestCloseTab"
          @new-tab="newNote('local')"
          @close-others="requestCloseOthers"
          @close-right="requestCloseRight"
          @close-all="requestCloseAll"
        />

        <StartView
          v-if="!activeTab"
          :items="startItems"
          :session="session"
          :loading="loadingRestore"
          @new-local="newNote('local')"
          @open-file="openFileDialog()"
          @open-item="selectItem"
          @login="openLogin"
          @expand-library="sidebarOpen = true"
        />

        <DocumentView
          v-else
          :tab="activeTab"
          :theme="editorTheme"
          :folder-options="folderOptions"
          :session="session"
          @update:title="updateTabTitle(activeTab, $event)"
          @update:content="updateTabContent(activeTab, $event)"
          @update:folder="updateTabFolder(activeTab, $event)"
          @scroll="updateScroll(activeTab, $event)"
          @action="onDocumentAction"
        />
      </main>
    </div>

    <StatusBar
      :tab="activeTab"
      :session="session"
      :online="online"
      :pending="pendingOutbox"
      :folder-label="activeTab?.note && !activeTab.note.localOnly ? '云端' : activeTab?.note ? folderPath(activeTab.note.folderId) : ''"
      :message="message"
      :error="error"
      :has-error-detail="!!errorDetail"
      :sync-error="syncError"
      @show-error="showErrorDetails"
    />

    <QuickOpen
      :open="quickOpen"
      :items="quickItems"
      :actions="quickActions"
      :session="!!session"
      @close="quickOpen = false"
      @select="openQuickItem"
      @action="onQuickAction"
    />

    <ImportDialog
      :open="importOpen"
      :default-title="importTitle"
      :can-server="!!session"
      :folders="serverFolderOptions"
      :local-folders="localFolderOptions"
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
        <h3 class="text-sm font-medium">同步到云端</h3>
        <p class="mt-1 text-[11px] leading-relaxed text-app-muted">
          「{{ promoteTab?.title || promoteNoteRow?.title || '未命名' }}」将移动到当前账号；本地文件夹不会同步，
          请在下方选择云端文件夹。
        </p>
        <label class="mt-4 block text-xs text-app-muted">
          文件夹
          <select
            v-model="promoteFolderId"
            class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          >
            <option value="">未分类</option>
            <option v-for="folder in serverFolderOptions" :key="folder.id" :value="folder.id">{{ folder.label }}</option>
          </select>
        </label>
        <div class="mt-5 flex justify-end gap-2">
          <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg" type="button" @click="promoteOpen = false">
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

    <UnsavedDialog
      :open="!!closeDialog"
      :mode="closeDialog?.mode ?? 'tab'"
      :files="closeDialog?.files ?? []"
      @save-all="confirmCloseDialog('save')"
      @discard-all="confirmCloseDialog('discard')"
      @cancel="confirmCloseDialog('cancel')"
      @one-by-one="confirmCloseDialog('one-by-one')"
    />

    <ErrorDetailsDialog
      :open="errorOpen"
      :summary="error"
      :detail="errorDetail"
      :kind="errorKind || 'unknown'"
      @close="errorOpen = false"
    />

    <div
      v-if="diffOpen && activeTab?.remoteSnapshot"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6"
    >
      <div class="flex max-h-[85vh] w-full max-w-5xl flex-col rounded-xl border border-app-border bg-app-panel">
        <header class="flex items-start justify-between border-b border-app-border px-4 py-3">
          <div>
            <h3 class="text-sm font-medium">冲突对比</h3>
            <p class="mt-0.5 text-[11px] text-app-muted">
              云端 v{{ activeTab.remoteVersion }} ↔ 本地；红色为云端独有，绿色为本地独有
            </p>
          </div>
          <button class="rounded border border-app-border px-2 py-1 text-xs hover:bg-app-bg" type="button" @click="diffOpen = false">
            关闭
          </button>
        </header>
        <div class="border-b border-app-border px-4 py-2 text-xs">
          <span class="text-app-muted">标题：</span>
          <span class="text-red-300">{{ activeTab.remoteSnapshot.title }}</span>
          <span class="mx-1 text-app-muted">→</span>
          <span class="text-emerald-300">{{ activeTab.title }}</span>
        </div>
        <div class="min-h-0 flex-1 overflow-auto bg-app-bg px-4 py-2 font-mono text-[12px] leading-5">
          <div
            v-for="(line, index) in diffLines"
            :key="index"
            class="whitespace-pre-wrap"
            :class="line.type === 'del' ? 'bg-red-500/10 text-red-300' : line.type === 'add' ? 'bg-emerald-500/10 text-emerald-300' : 'text-app-muted'"
          >
            <span class="mr-2 inline-block w-4 select-none text-right opacity-60">{{ line.type === 'del' ? '-' : line.type === 'add' ? '+' : ' ' }}</span>{{ line.text || ' ' }}
          </div>
        </div>
        <footer class="flex items-center justify-end gap-2 border-t border-app-border px-4 py-3">
          <button class="rounded border border-brand-400/60 px-3 py-1 text-xs text-brand-400 hover:bg-brand-500/10" type="button" @click="resolveConflict('local')">
            以本地为准
          </button>
          <button class="rounded border border-app-border px-3 py-1 text-xs hover:bg-app-bg" type="button" @click="resolveConflict('remote')">
            以云端为准
          </button>
        </footer>
      </div>
    </div>

    <ContextMenu
      :open="!!listMenu"
      :x="listMenu?.x ?? 0"
      :y="listMenu?.y ?? 0"
      :items="listMenuItems"
      :min-width="196"
      @select="runListMenu"
      @close="listMenu = null"
    />

    <ContextMenu
      :open="!!textMenu"
      :x="textMenu?.x ?? 0"
      :y="textMenu?.y ?? 0"
      :items="textMenuItems"
      :min-width="200"
      @select="runTextMenu"
      @close="textMenu = null"
    />
  </div>
</template>
