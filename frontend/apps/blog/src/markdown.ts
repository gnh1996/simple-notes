import MarkdownIt from 'markdown-it'

import { MarkdownItGitHubAlerts, hljs, markdownDialect } from '@simple-notes/shared'

/**
 * 方言唯一浏览器实现（docs/markdown-dialect.md）：
 * 配置来自 @simple-notes/shared，与 admin 的 md-editor-v3 保持语义一致。
 */
const md = new MarkdownIt({
  ...markdownDialect,
  highlight(code: string, language: string): string {
    if (!language) {
      return ''
    }
    const name = hljs.getLanguage(language) ? language : 'plaintext'
    try {
      return hljs.highlight(code, { language: name, ignoreIllegals: true }).value
    } catch {
      return ''
    }
  },
})

md.use(MarkdownItGitHubAlerts)

const defaultHeadingOpen = md.renderer.rules.heading_open
  ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

function slugify(text: string): string {
  return (
    String(text)
      .trim()
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section'
  )
}

md.renderer.rules.heading_open = (tokens, idx, options, env, self) => {
  const inline = tokens[idx + 1]
  const text = inline && inline.content ? inline.content.replace(/<[^>]+>/g, '') : ''
  tokens[idx].attrSet('id', slugify(text))
  return defaultHeadingOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(source: string | null | undefined): string {
  return source ? md.render(source) : ''
}
