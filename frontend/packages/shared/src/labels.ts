/**
 * 固定枚举 / 状态码的展示文案（前端 UI 层唯一来源）。
 *
 * 这些值由后端代码枚举或固定状态码定义，不是可运营数据，因此不建数据库字典表；
 * 使用 `Record<值, 文案>` 让遗漏在 typecheck 阶段暴露。新增枚举值时先补这里。
 */

export type StorageModeValue = 'LOCAL' | 'OSS' | 'DATABASE'
export type PublishStatusValue = 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED'
export type NoteKindValue = 'NOTE' | 'POST'
export type ClientTypeValue = 'ADMIN' | 'BLOG' | 'DESKTOP'
export type MenuTypeValue = 'ENTRY' | 'MENU' | 'ACTION'

/** 附件存储介质（docs/attachment-storage.md） */
export const STORAGE_MODE_LABELS: Record<StorageModeValue, string> = {
  LOCAL: '本地磁盘',
  OSS: '对象存储（R2）',
  DATABASE: '数据库',
}

/** 博客文章发布状态 */
export const PUBLISH_STATUS_LABELS: Record<PublishStatusValue, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  UNPUBLISHED: '已下架',
}

/** 内容类型：随手记 / 博客文章 */
export const NOTE_KIND_LABELS: Record<NoteKindValue, string> = {
  NOTE: '随手记',
  POST: '博客文章',
}

/** 菜单所属客户端 */
export const CLIENT_TYPE_LABELS: Record<ClientTypeValue, string> = {
  ADMIN: '管理端',
  BLOG: '博客端',
  DESKTOP: '桌面端',
}

/** 菜单节点类型 */
export const MENU_TYPE_LABELS: Record<MenuTypeValue, string> = {
  ENTRY: '入口',
  MENU: '菜单',
  ACTION: '操作',
}

/** 通用启用状态（1 = 启用，0 = 停用） */
export const ENABLED_STATUS_LABELS = {
  1: '启用',
  0: '停用',
} as const

/** 健康检查哨兵值：后端返回 OK 表示正常，其余为异常原因 */
export const STORAGE_HEALTH_OK = 'OK'

/** 按枚举值取文案；未知值原样返回，避免新后端值导致页面空白 */
export function labelOf<T extends string>(
  labels: Record<T, string>,
  value: string | null | undefined,
): string {
  if (value === null || value === undefined) return ''
  return labels[value as T] ?? value
}

/** 发布状态文案；缺省按草稿处理（回收站旧数据可能没有该字段） */
export function publishStatusLabel(status: string | null | undefined): string {
  return status ? labelOf(PUBLISH_STATUS_LABELS, status) : PUBLISH_STATUS_LABELS.DRAFT
}

/** 内容类型文案 */
export function noteKindLabel(kind: string | null | undefined): string {
  return kind ? labelOf(NOTE_KIND_LABELS, kind) : '—'
}

/** 启用状态文案 */
export function enabledStatusLabel(status: number | null | undefined): string {
  return status === 1 ? ENABLED_STATUS_LABELS[1] : ENABLED_STATUS_LABELS[0]
}

/** 健康检查是否正常 */
export function isStorageHealthy(value: string): boolean {
  return value === STORAGE_HEALTH_OK
}

/** 健康检查文案：正常 / 异常（原因） */
export function storageHealthLabel(value: string): string {
  if (isStorageHealthy(value)) return '正常'
  return value ? `异常（${value}）` : '异常'
}
