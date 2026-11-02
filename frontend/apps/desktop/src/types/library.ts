import type { RecentFile } from '../../bindings/cc.naihao/simplenotes/desktop/internal/localfile/models'
import type { NoteRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import type { TabSource } from './document'

export type LibraryFilter = 'file' | 'local' | 'server'

export interface LibraryItem {
  key: string
  source: TabSource
  title: string
  subtitle: string
  updatedAt: number
  path?: string
  note?: NoteRow
  recent?: RecentFile
  pinned?: boolean
  conflict?: boolean
  dirty?: boolean
  localOnly?: boolean
  exists?: boolean
}
