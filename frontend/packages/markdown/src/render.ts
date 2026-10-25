import type VditorMethodType from 'vditor/dist/method'

import type { VditorPreviewOptions } from './index'

declare global {
  interface Window {
    Vditor?: typeof VditorMethodType
  }
}

function slugify(text: string): string {
  return (
    text
      .trim()
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section'
  )
}

function ensureHeadingIds(element: HTMLElement): void {
  for (const heading of element.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6')) {
    if (!heading.id) {
      heading.id = slugify(heading.textContent ?? '')
    }
  }
}

let methodPromise: Promise<typeof VditorMethodType> | undefined

async function loadMethod(cdn: string): Promise<typeof VditorMethodType> {
  if (window.Vditor) {
    return window.Vditor
  }
  methodPromise ??= new Promise<typeof VditorMethodType>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = `${cdn}/dist/method.min.js`
    script.onload = () => {
      if (window.Vditor) {
        resolve(window.Vditor)
      } else {
        reject(new Error('Vditor method 未注册到全局'))
      }
    }
    script.onerror = () => reject(new Error('Vditor method 加载失败'))
    document.head.appendChild(script)
  })
  return methodPromise
}

/**
 * 博客发布侧渲染入口：Lute（与编辑器同一份配置）→ 清洗 → 高亮 / 数学 / Mermaid。
 *
 * 运行时按需加载 `method.min.js`（只含静态渲染方法，不含编辑器 UI），
 * 避免把完整 Vditor 打进博客 bundle；Math / Mermaid 在清洗之后生成。
 */
export async function renderToElement(
  element: HTMLElement,
  source: string,
  options: VditorPreviewOptions,
): Promise<void> {
  const cdn = options.cdn ?? ''
  const method = await loadMethod(cdn)

  const html = await method.md2html(source, options)
  element.innerHTML = html
  ensureHeadingIds(element)

  method.highlightRender(options.hljs, element, cdn)
  // 博客使用自己的纸墨主题 token 颜色，移除 Vditor 注入的 hljs 主题样式，避免覆盖。
  document.getElementById('vditorHljsStyle')?.remove()
  method.mathRender(element, { cdn, math: options.math })
  method.mermaidRender(element, cdn, options.mode)
}
