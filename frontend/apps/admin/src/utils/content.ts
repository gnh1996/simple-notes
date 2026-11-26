import type { TagBrief } from '@simple-notes/api'

/** 博客前台地址：开发期默认 8080（后端壳 + jar 内 runtime）；部署时用 VITE_BLOG_URL 覆盖 */
const BLOG_BASE = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:8080'

export function publicPostUrl(slug?: string | null): string | null {
  return slug ? `${BLOG_BASE.replace(/\/$/, '')}/posts/${encodeURIComponent(slug)}` : null
}

export function tagNames(tags?: TagBrief[] | null): string[] {
  return (tags ?? []).map((tag) => tag.name)
}

/**
 * 去掉 Markdown 语法、保留可读文本（不解析完整 Markdown）。
 *
 * 只剥“结构标记”，不做字符集删除：`C#` / `C++` / `well-known` 这类词内符号必须保留。
 * 传入 title 时，若首个标题与文章标题相同则丢弃，避免摘要以重复标题开头。
 */
export function stripMarkdown(content: string, title?: string): string {
  let text = content
  const trimmedTitle = title?.trim()
  if (trimmedTitle) {
    text = text.replace(
      /^\s*#{1,6}[ \t]+([^\n]+?)[ \t]*(?:\r?\n|$)/,
      (all, heading: string) => (heading.trim() === trimmedTitle ? '' : all),
    )
  }
  return (
    text
      // 代码块 / 行内代码：内容不进摘要
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/~~~[\s\S]*?~~~/g, ' ')
      .replace(/`[^`\n]*`/g, ' ')
      // 图片整段丢弃，链接只留文字
      .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/\[\^\d+\]/g, ' ')
      // HTML 标签（方言允许安全行内 HTML）
      .replace(/<\/?[a-zA-Z][^>\n]*>/g, ' ')
      // 行首结构标记
      .replace(/^[ \t]{0,3}#{1,6}[ \t]+/gm, '')
      .replace(/^[ \t]{0,3}>[ \t]?/gm, '')
      .replace(/^[ \t]{0,3}(?:[-*+]|\d{1,3}[.)])[ \t]+/gm, '')
      // 表格分隔行 + 表格竖线
      .replace(/^[ \t|:-]*-[ \t|:-]*$/gm, ' ')
      .replace(/\|/g, ' ')
      // 成对强调标记；单字符不删，避免误伤词内符号
      .replace(/\*\*|__|~~|==/g, '')
      .replace(/\*([^*\n]+)\*/g, '$1')
      .replace(/[ \t]+/g, ' ')
      .replace(/\s*\n\s*/g, '\n')
      .replace(/\n{2,}/g, '\n')
      .trim()
  )
}

/**
 * 从 Markdown 粗略提取纯文本摘要：取正文开头，累加到句边界后截断并补省略号。
 *
 * 不做完整 Markdown 解析；结果用于摘要输入框，AI 不可用 / 未配置时的永久兜底。
 */
export function summarizeMarkdown(content: string, maxLength = 100, title?: string): string {
  const plain = stripMarkdown(content, title).replace(/\s+/g, ' ').trim()
  if (!plain) return ''
  if (plain.length <= maxLength) return plain

  // 目标略小于上限，尽量让切点落在句末而不是刚好顶到上限
  const target = Math.max(50, maxLength - 15)
  const parts = plain.split(/(?<=[。！？!?；;])/).filter((part) => part.trim())

  let picked = ''
  for (const part of parts) {
    if (part.length > maxLength) break
    if (picked.length + part.length > maxLength) break
    picked += part
    if (picked.length >= target) break
  }

  // 单句就超长：在句内回退到最近的逗号 / 空格，避免直接砍在词中间
  if (!picked) {
    picked = plain.slice(0, maxLength)
    const cut = Math.max(
      picked.lastIndexOf('，'),
      picked.lastIndexOf(','),
      picked.lastIndexOf('、'),
      picked.lastIndexOf('；'),
      picked.lastIndexOf(';'),
      picked.lastIndexOf('：'),
      picked.lastIndexOf(':'),
      picked.lastIndexOf(' '),
    )
    if (cut >= 60) picked = picked.slice(0, cut)
  }

  const text = picked.trim().replace(/[，,、；;：:\s]+$/, '')
  return `${text || plain.slice(0, maxLength)}…`
}
