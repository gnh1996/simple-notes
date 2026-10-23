import hljs from 'highlight.js/lib/common'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import properties from 'highlight.js/lib/languages/properties'
import xml from 'highlight.js/lib/languages/xml'
import { allToolbar, config, type ToolbarNames } from 'md-editor-v3'
import MarkdownItGitHubAlerts from 'markdown-it-github-alerts'

// 方言附录 A 中 common 未包含的语言；Vue 以 XML 语法近似高亮
hljs.registerLanguage('dockerfile', dockerfile)
hljs.registerLanguage('properties', properties)
hljs.registerLanguage('vue', xml)

/**
 * admin / blog 共享的 md-editor-v3 配置（docs/markdown-dialect.md §6）。
 *
 * - `breaks: false` 对齐 CommonMark 软换行；
 * - 原生 HTML 关闭（html: false），由渲染端转义；
 * - 追加 GitHub Alerts，禁用内置 `!!!` admonition；
 * - 过滤 sub / sup 等未收扩展（Katex / ECharts 由组件 props 关闭）。
 */
let configured = false

export function configureMarkdown(): void {
  if (configured) {
    return
  }
  configured = true
  config({
    // 代码高亮使用本地 highlight.js，不依赖运行时 CDN
    editorExtensions: {
      highlight: {
        instance: hljs,
      },
    },
    markdownItConfig(md) {
      md.options.breaks = false
      md.options.html = false
      md.options.linkify = true
      md.use(MarkdownItGitHubAlerts)
    },
    markdownItPlugins(plugins) {
      return plugins.filter(
        (plugin) => !['admonition', 'sub', 'sup'].includes(plugin.type),
      )
    },
  })
}

/** 未收扩展对应的工具栏入口不展示 */
export const markdownToolbars = allToolbar.filter(
  (name) => !['katex', 'sub', 'sup', 'echarts'].includes(name),
) as ToolbarNames[]

/** 所有编辑器 / 预览组件的公共 props（Mermaid 保留，Katex / ECharts 关闭） */
export const markdownCommonProps = {
  noKatex: true,
  noEcharts: true,
  previewTheme: 'github',
  codeTheme: 'atom',
} as const
