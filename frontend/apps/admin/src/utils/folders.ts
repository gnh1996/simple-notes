import type { NoteFolderResponse } from '@simple-notes/api'

/**
 * 把平铺的文件夹列表解析为「父 / 子」路径。
 *
 * <p>路径只用于展示（列表 / 下拉）；异常数据（父级缺失或成环）按中断处理，不影响渲染。
 */
export function buildFolderPaths(folders: NoteFolderResponse[]): Map<string, string> {
  const byId = new Map(folders.map((folder) => [folder.id, folder]))
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
    const parentId = folder.parentId ?? ''
    const parent = parentId ? resolve(parentId, guard + 1) : ''
    const value = parent ? `${parent} / ${folder.name}` : folder.name
    paths.set(id, value)
    return value
  }
  for (const folder of folders) {
    resolve(folder.id)
  }
  return paths
}
