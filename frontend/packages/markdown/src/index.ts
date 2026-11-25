import DOMPurify from 'dompurify'
import type Vditor from 'vditor'

/** Vditor 编辑器选项（从 Vditor 构造器推导，避免复制一份类型）。 */
export type VditorEditorOptions = NonNullable<ConstructorParameters<typeof Vditor>[1]>
/** Vditor 静态预览选项。 */
export type VditorPreviewOptions = NonNullable<Parameters<typeof Vditor.preview>[2]>

/**
 * 方言 v2 的单一语义源（docs/markdown-dialect.md）。
 *
 * 允许数学、emoji、安全行内 HTML、脚注/上下标/mark；禁止未收的图表扩展。
 * 编辑器预览与博客发布都从这份配置取，避免两端漂移。
 */
export const vditorMarkdown = {
  autoSpace: false,
  callout: true,
  inlineMath: true,
  paragraphBeginningSpace: false,
  fixTermTypo: false,
  toc: false,
  footnotes: true,
  imageCaption: false,
  codeBlockPreview: true,
  mathBlockPreview: true,
  sanitize: true,
  linkBase: '',
  linkPrefix: '',
  listStyle: false,
  mark: true,
  gfmAutoLink: true,
  sup: true,
  sub: true,
} as const

export const vditorHljs = {
  enable: true,
  lineNumber: false,
} as const

export const vditorMath = {
  engine: 'KaTeX',
} as const

/**
 * 桌面端精简工具栏（`docs/desktop-layout.md` §2.7）。
 *
 * Vditor 默认工具栏在约 900px 以下会换行：桌面端 1180 窗口 + 440 资料库时正文只有 738px，
 * 换行会白吃 35px 并出现「计数器掉到第二行」的错位。这里只保留写作高频项，
 * 最窄约 480px 仍单行。record / outline / export / fullscreen / info / help 不保留
 * 也不做等价入口，理由见 `docs/desktop-layout.md` §2.7.3（涉及外部设备 / 文件落盘 /
 * 额外推挤面板的入口一律走受控的 `⋯` 菜单或专门设计，不放工具栏）。
 * admin 仍使用 Vditor 默认全量工具栏，不引用本常量。
 */
export const compactEditorToolbar = [
  'head',
  'bold',
  'italic',
  'strike',
  '|',
  'link',
  'list',
  'ordered-list',
  'check',
  'quote',
  '|',
  'code',
  'inline-code',
  'table',
  '|',
  'upload',
  '|',
  'undo',
  'redo',
  'edit-mode',
] as const

/** 渲染前的 HTML 清洗；KaTeX / Mermaid 在清洗之后生成，不会被误删。 */
export function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'textarea', 'select', 'option', 'button', 'iframe', 'object', 'embed', 'link', 'meta'],
    FORBID_ATTR: ['srcdoc', 'formaction'],
    ADD_ATTR: ['target', 'rel', 'disabled', 'checked', 'type'],
    ALLOW_DATA_ATTR: true,
  })
}

export interface EditorSetup {
  cdn: string
  value: string
  theme: 'light' | 'dark'
  cacheId: string
  placeholder?: string
  height?: number | string
  minHeight?: number
  onInput?: (value: string) => void
  /** 返回插入编辑器的 Markdown 片段（通常是图片语法）。 */
  onUpload?: (files: File[]) => Promise<string | null>
  /** 相对链接 / 图片的解析基准（桌面端本地文件 / 本地笔记使用）。 */
  linkBase?: string
  uploadAccept?: string
  uploadMax?: number
  uploadMultiple?: boolean
  /** 自定义工具栏项；不传则使用 Vditor 全量默认工具栏（admin）。 */
  toolbar?: readonly string[]
  /** 是否显示 Vditor 右下角计数器；默认显示，桌面端关闭（字数由状态栏承担）。 */
  counter?: boolean
}

export function createEditorOptions(setup: EditorSetup): VditorEditorOptions {
  const options: VditorEditorOptions = {
    cdn: setup.cdn,
    value: setup.value,
    mode: 'ir',
    height: setup.height ?? '100%',
    minHeight: setup.minHeight,
    placeholder: setup.placeholder,
    lang: 'zh_CN',
    theme: setup.theme === 'dark' ? 'dark' : 'classic',
    cache: { id: setup.cacheId, enable: false },
    toolbarConfig: { pin: true },
    counter: { enable: setup.counter ?? true, type: 'text' },
    preview: {
      delay: 200,
      hljs: {
        ...vditorHljs,
        style: setup.theme === 'dark' ? 'github-dark' : 'github',
      },
      math: vditorMath,
      markdown: setup.linkBase ? { ...vditorMarkdown, linkBase: setup.linkBase } : vditorMarkdown,
      theme: { current: setup.theme },
      transform: sanitizeHtml,
    },
  }

  if (setup.onInput) {
    options.input = setup.onInput
  }

  if (setup.toolbar) {
    options.toolbar = [...setup.toolbar]
  }

  // 注意：不能把 upload 设为 undefined；Vditor 的 options merge 会因该键存在而初始化失败。
  if (setup.onUpload) {
    options.upload = {
      url: '',
      // Vditor 的 handler 类型把 Promise<string> / Promise<null> 拆成联合，无法直接表达
      // Promise<string | null>；运行时两种返回值都支持，这里只做类型收口。
      handler: ((files: File[]) => setup.onUpload!(files)) as NonNullable<VditorEditorOptions['upload']>['handler'],
      ...(setup.uploadAccept ? { accept: setup.uploadAccept } : {}),
      ...(setup.uploadMax ? { max: setup.uploadMax } : {}),
      ...(setup.uploadMultiple !== undefined ? { multiple: setup.uploadMultiple } : {}),
    }
  }

  return options
}

export function createPreviewOptions(options: {
  cdn: string
  theme: 'light' | 'dark'
}): VditorPreviewOptions {
  return {
    cdn: options.cdn,
    mode: options.theme,
    anchor: 0,
    lang: 'zh_CN',
    icon: 'ant',
    hljs: {
      ...vditorHljs,
      style: options.theme === 'dark' ? 'github-dark' : 'github',
    },
    math: vditorMath,
    markdown: vditorMarkdown,
    theme: { current: options.theme },
    transform: sanitizeHtml,
  }
}

