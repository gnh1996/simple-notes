/**
 * 内置默认主题（docs/blog-runtime-theme.md §4.2）。
 *
 * 唯一源：blog runtime 的兜底主题 + 管理端「新建主题」的起点。
 * 后端组装壳时同样需要兜底，`backend/src/main/resources/blog/default-theme/`
 * 由 `pnpm sync:theme` 同步，CI 校验哈希（见 frontend/package.json）。
 */
export const starterThemeMeta = {
  name: '内置默认主题',
  contract: 1,
} as const
