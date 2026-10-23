import type { TagBrief } from '@simple-notes/api'

/** 博客前台地址：开发期默认 5174；部署时用 VITE_BLOG_URL 覆盖 */
const BLOG_BASE = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:5174'

export function publicPostUrl(slug?: string | null): string | null {
  return slug ? `${BLOG_BASE.replace(/\/$/, '')}/posts/${encodeURIComponent(slug)}` : null
}

export function tagNames(tags?: TagBrief[] | null): string[] {
  return (tags ?? []).map((tag) => tag.name)
}

/** 从 Markdown 粗略提取纯文本摘要（发布弹窗自动填充用；不做完整 Markdown 解析） */
export function summarizeMarkdown(content: string, maxLength = 160): string {
  const text = content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`]*`/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_~>#-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.slice(0, maxLength)
}
