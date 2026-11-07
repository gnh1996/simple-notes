/**
 * 版本展示（docs/release.md §4）：管理端版本来自构建注入，服务端版本来自 GET /api/version。
 *
 * 管理端是独立静态包，与后端 jar 可能不同步——同时展示两个版本，
 * "管理端已更新、服务端没更新"的部署问题一眼可见。
 */
import type { VersionResponse } from '@simple-notes/api'

import { metaApi } from '@/api'

/** 管理端自身版本：构建时注入（vite define <- git describe，无 git 时 0.0.0-dev）。 */
export const APP_VERSION: string = __APP_VERSION__

export type { VersionResponse }

/** 查询服务端版本；失败返回 null，调用方展示"未知"，不阻塞页面。 */
export async function fetchServerVersion(): Promise<VersionResponse | null> {
  try {
    const info = await metaApi.version()
    if (typeof info?.version !== 'string') return null
    return info
  } catch {
    return null
  }
}
