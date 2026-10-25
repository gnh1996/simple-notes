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
 * Markdown 方言的单一语义源（docs/markdown-dialect.md §6）。
 *
 * admin（md-editor-v3）与 blog runtime（markdown-it）都从这里取配置，
 * 避免两端各自维护一份 breaks / html / linkify / 扩展集合。
 */
export const markdownDialect = {
  breaks: false,
  html: false,
  linkify: true,
} as const

export { hljs, MarkdownItGitHubAlerts }
