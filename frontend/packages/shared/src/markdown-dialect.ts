import hljs from 'highlight.js/lib/common'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import properties from 'highlight.js/lib/languages/properties'
import xml from 'highlight.js/lib/languages/xml'
import MarkdownItGitHubAlerts from 'markdown-it-github-alerts'

// 方言附录 A 中 common 未包含的语言；Vue 以 XML 语法近似高亮
hljs.registerLanguage('dockerfile', dockerfile)
hljs.registerLanguage('properties', properties)
hljs.registerLanguage('vue', xml)

/**
 * 桌面端过渡期的 Markdown 方言配置（docs/markdown-dialect.md §6）。
 *
 * desktop（md-editor-v3 / markdown-it）从这里取配置；admin / blog 已改用
 * `@simple-notes/markdown` 的 Vditor + Lute（方言 v2），不要从本文件取。
 */
export const markdownDialect = {
  breaks: false,
  html: false,
  linkify: true,
} as const

export { hljs, MarkdownItGitHubAlerts }
