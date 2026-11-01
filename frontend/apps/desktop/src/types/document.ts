import type { RemoteSnapshot } from '../../bindings/cc.naihao/simplenotes/desktop/internal/notes/models'
import type { NoteRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'

export type TabSource = 'file' | 'local' | 'server'
export type SaveState =
  | 'idle'
  | 'saving'
  | 'saved'
  | 'synced'
  | 'offline'
  | 'conflict'
  | 'modified'
  | 'error'
  | 'missing'

export interface FileMeta {
  path: string
  name: string
  hash: string
  mtimeMs: number
  size: number
  lineEnding: string
  bom: boolean
}

export interface NoteMeta {
  id: string
  scopeId: string
  kind: string
  folderId: string
  localOnly: boolean
  remoteVersion: number
  baseVersion: number
  publishStatus: string
  hasUnpublishedChanges: boolean
  tags: string[]
  pinned: boolean
  contentLoaded: boolean
  conflict: boolean
  remoteDeleted: boolean
  updatedAt: number
}

export interface DocumentTab {
  key: string
  source: TabSource
  title: string
  content: string
  loaded: boolean
  loading: boolean
  dirty: boolean
  saveState: SaveState
  saveMessage: string
  scrollTop: number
  file?: FileMeta
  note?: NoteMeta
  error?: string
  remoteSnapshot?: RemoteSnapshot | null
  remoteTitle?: string
  remoteVersion?: number
}

export interface PersistedTab {
  source: TabSource
  path?: string
  name?: string
  id?: string
  scopeId?: string
}

export interface PersistedTabs {
  tabs: PersistedTab[]
  active: number
}

export const MAX_TABS = 12

export function fileKey(path: string): string {
  return `file:${path}`
}

export function noteKey(source: 'local' | 'server', scopeId: string, id: string): string {
  return source === 'local' ? `local:${id}` : `server:${scopeId}:${id}`
}

export function tabKeyForNote(note: NoteRow, scopeId: string): string {
  return note.localOnly ? noteKey('local', 'local', note.id) : noteKey('server', scopeId, note.id)
}

export function noteMetaFromRow(note: NoteRow, scopeId: string): NoteMeta {
  return {
    id: note.id,
    scopeId: note.localOnly ? 'local' : scopeId,
    kind: note.kind,
    folderId: note.folderId ?? '',
    localOnly: note.localOnly,
    remoteVersion: note.remoteVersion,
    baseVersion: note.baseVersion,
    publishStatus: note.publishStatus ?? '',
    hasUnpublishedChanges: note.hasUnpublishedChanges,
    tags: note.tags ?? [],
    pinned: note.pinned,
    contentLoaded: note.contentLoaded,
    conflict: note.conflict,
    remoteDeleted: note.remoteDeleted,
    updatedAt: note.updatedAt,
  }
}

export function tabFromNote(note: NoteRow, scopeId: string): DocumentTab {
  const meta = noteMetaFromRow(note, scopeId)
  return {
    key: noteKey(meta.localOnly ? 'local' : 'server', meta.scopeId, note.id),
    source: meta.localOnly ? 'local' : 'server',
    title: note.title || '未命名',
    content: note.content ?? '',
    loaded: meta.contentLoaded,
    loading: false,
    dirty: note.dirty,
    saveState: note.conflict ? 'conflict' : meta.localOnly ? 'saved' : 'idle',
    saveMessage: '',
    scrollTop: 0,
    note: meta,
    remoteSnapshot: null,
    remoteTitle: '',
    remoteVersion: note.remoteVersion,
  }
}
