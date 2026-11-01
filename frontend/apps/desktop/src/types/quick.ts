import type { NoteRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'
import type { TabSource } from './document'

export interface QuickItem {
  key: string
  source: TabSource
  title: string
  subtitle: string
  updatedAt: number
  note?: NoteRow
  path?: string
  opened?: boolean
}

export interface QuickAction {
  id: 'new-local' | 'open-file' | 'toggle-sidebar' | 'login'
  title: string
  hint?: string
}
