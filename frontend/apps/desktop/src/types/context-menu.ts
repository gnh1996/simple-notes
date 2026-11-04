/** 通用右键菜单项：separator 只占位，不需要 label。 */
export interface ContextMenuItem {
  key: string
  label?: string
  /** 右侧灰色提示（快捷键 / 禁用原因）。 */
  hint?: string
  disabled?: boolean
  danger?: boolean
  separator?: boolean
}
