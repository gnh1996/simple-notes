import type { FolderRow } from '../../bindings/cc.naihao/simplenotes/desktop/internal/store/models'

/** 侧栏文件夹树节点：depth 从 1 开始，hasChildren 用于展开 / 删除提示。 */
export interface FolderNode extends FolderRow {
  depth: number
  hasChildren: boolean
  path: string
}

/** 下拉 / 选择器使用的扁平选项（label 为「父 / 子」路径）。 */
export interface FolderOption {
  id: string
  label: string
}
