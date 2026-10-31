import type { Archive, PostDetail, PostSummary, SitePage, Tag } from './types'

/**
 * 主题预览示例数据（docs/blog-runtime-theme.md §7.2）。
 *
 * 只在 `preview` 壳且 URL 带 `fixtures=1` 时启用，供空站点 / 新主题检查版式；
 * 正常访客与真实内容页面不会走这里。
 */

const tags: Tag[] = [
  { id: 'fixture-note', name: '随笔', count: 3 },
  { id: 'fixture-tech', name: '技术', count: 2 },
  { id: 'fixture-reading', name: '阅读', count: 1 },
]

const tagBrief = (id: string) => {
  const tag = tags.find((item) => item.id === id)
  return tag ? { id: tag.id, name: tag.name } : null
}

function summary(
  slug: string,
  title: string,
  brief: string,
  tagIds: string[],
  displayAt: string,
  pinned = false,
): PostSummary {
  return {
    slug,
    title,
    summary: brief,
    coverUrl: null,
    tags: tagIds.map(tagBrief).filter((tag): tag is { id: string; name: string } => tag !== null),
    pinned,
    firstPublishedAt: displayAt,
    lastPublishedAt: displayAt,
    authoredAt: displayAt,
    displayAt,
    readingMinutes: 3,
  }
}

export const fixturePosts: PostSummary[] = [
  summary(
    'preview-hello',
    '主题预览：一页纸的呼吸感',
    '这是用于检查标题层级、摘要、标签与日期的示例文章。替换真实内容后，版式应当保持一致。',
    ['fixture-note', 'fixture-tech'],
    '2026-10-30T09:00:00Z',
    true,
  ),
  summary(
    'preview-reading',
    '把阅读区收窄一点',
    '正文宽度、行高与段落间距是主题最先被感知的部分。',
    ['fixture-reading'],
    '2026-10-21T09:00:00Z',
  ),
  summary(
    'preview-archive',
    '归档应该像书脊',
    '月份索引和月内分页让时间线保持轻盈。',
    ['fixture-note'],
    '2026-09-12T09:00:00Z',
  ),
]

export const fixtureTags = tags

export const fixtureArchives: Archive[] = [
  { year: 2026, month: 10, count: 2 },
  { year: 2026, month: 9, count: 1 },
]

const content = `# 主题预览

这是一篇示例文章，用来检查 **标题、正文、引用与代码块** 在主题中的表现。

> [!NOTE]
> 提示块、任务列表与数学公式都属于方言 v2 的白名单。

- [x] 标题层级
- [x] 摘要与标签
- [ ] 换成你的真实内容

\`\`\`ts
const hello = (name: string) => \`Hello, \${name}\`
\`\`\`

行内公式 $a^2 + b^2 = c^2$ 与脚注[^1] 也应正常渲染。

[^1]: 这是示例脚注。
`

export function fixturePost(slug: string): PostDetail | null {
  const index = fixturePosts.findIndex((item) => item.slug === slug)
  const found = index >= 0 ? fixturePosts[index] : fixturePosts[0]
  if (!found) return null
  const currentIndex = fixturePosts.indexOf(found)
  return {
    ...found,
    content,
    authorName: '示例作者',
    prevPost: currentIndex > 0 ? fixturePosts[currentIndex - 1] : null,
    nextPost: currentIndex < fixturePosts.length - 1 ? fixturePosts[currentIndex + 1] : null,
  }
}

export const fixturePage: SitePage = {
  id: 'fixture-about',
  slug: 'about',
  title: '关于',
  content: '这是一个用于主题预览的独立页面。真实内容在站点设置中维护。',
  visible: true,
  sort: 0,
  updatedAt: '2026-10-30T09:00:00Z',
}
