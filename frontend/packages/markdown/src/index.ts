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
    counter: { enable: true, type: 'text' },
    preview: {
      delay: 200,
      hljs: {
        ...vditorHljs,
        style: setup.theme === 'dark' ? 'github-dark' : 'github',
      },
      math: vditorMath,
      markdown: vditorMarkdown,
      theme: { current: setup.theme },
      transform: sanitizeHtml,
    },
  }

  if (setup.onInput) {
    options.input = setup.onInput
  }

  // 注意：不能把 upload 设为 undefined；Vditor 的 options merge 会因该键存在而初始化失败。
  if (setup.onUpload) {
    options.upload = {
      url: '',
      handler: async (files: File[]): Promise<string> => (await setup.onUpload!(files)) ?? '',
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

