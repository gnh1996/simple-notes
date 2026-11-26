import type { NoteFolderResponse } from '@simple-notes/api'

/** 侧栏树节点：扁平化后带缩进层级、子级标记与完整路径。 */
export interface FolderTreeNode extends NoteFolderResponse {
  /** 从 1 开始的层级（根 = 1）。 */
  depth: number
  hasChildren: boolean
  /** 完整路径，用于 title 与下拉框展示。 */
  path: string
}

/** 与后端一致：文件夹最多 5 级；超出的脏数据只压缩缩进，不隐藏节点。 */
const MAX_DEPTH = 5

/**
 * 把平铺的文件夹列表展开成带 depth 的树，同级顺序沿用服务端（sort asc, createTime desc）。
 *
 * <p>父级缺失或成环的节点降级为根，避免脏数据在侧栏消失；`collapsedIds` 中的节点不展开子级。
 * 仅供展示，不参与筛选请求。
 */
export function buildFolderTree(
  folders: NoteFolderResponse[],
  collapsedIds: Iterable<string> = [],
): FolderTreeNode[] {
  const ids = new Set(folders.map((folder) => folder.id))
  const children = new Map<string, NoteFolderResponse[]>()
  for (const folder of folders) {
    const parentId = folder.parentId && ids.has(folder.parentId) ? folder.parentId : ''
    const siblings = children.get(parentId) ?? []
    siblings.push(folder)
    children.set(parentId, siblings)
  }

  const collapsed = new Set(collapsedIds)
  const reachable = new Set<string>()
  const markReachable = (folder: NoteFolderResponse): void => {
    if (reachable.has(folder.id)) {
      return
    }
    reachable.add(folder.id)
    for (const child of children.get(folder.id) ?? []) {
      markReachable(child)
    }
  }
  for (const root of children.get('') ?? []) {
    markReachable(root)
  }

  const visited = new Set<string>()
  const nodes: FolderTreeNode[] = []

  const emit = (folder: NoteFolderResponse, depth: number, parentPath: string): void => {
    // visited 同时兜住成环：脏数据最多出现一次，不会无限递归。
    if (visited.has(folder.id)) {
      return
    }
    visited.add(folder.id)
    const path = parentPath ? `${parentPath} / ${folder.name}` : folder.name
    nodes.push({
      ...folder,
      depth: Math.min(depth, MAX_DEPTH),
      hasChildren: (children.get(folder.id)?.length ?? 0) > 0,
      path,
    })
    if (collapsed.has(folder.id)) {
      return
    }
    for (const child of children.get(folder.id) ?? []) {
      emit(child, depth + 1, path)
    }
  }

  for (const root of children.get('') ?? []) {
    emit(root, 1, '')
  }
  // 只有从根不可达的成环节点才补成根；被折叠隐藏的子级不能在这里复活。
  for (const folder of folders) {
    if (!reachable.has(folder.id)) {
      emit(folder, 1, '')
    }
  }
  return nodes
}

/**
 * 把平铺的文件夹列表解析为「父 / 子」路径，用于下拉框与列表展示。
 *
 * <p>与侧栏树共用同一套父子解析，保证两处路径一致。
 */
export function buildFolderPaths(folders: NoteFolderResponse[]): Map<string, string> {
  return new Map(buildFolderTree(folders).map((node) => [node.id, node.path]))
}
