import { createPreviewOptions } from '@simple-notes/markdown'
import { renderToElement } from '@simple-notes/markdown/render'

/**
 * 博客发布侧渲染：Lute（与 admin 编辑器同一引擎/同一份方言配置）→ 清洗 → 代码高亮 / 数学 / Mermaid。
 *
 * 开发期 runtime 由 Vite 从 /src/runtime.ts 提供，public 资源在 /vditor；
 * 生产期 runtime.js 位于 <asset-base>/runtime.js，Vditor 资源同目录下的 /vditor。
 */
const runtimeScript = Array.from(document.scripts).find((script) =>
  /\/runtime\.js(?:\?.*)?$/.test(script.src),
)
const runtimeBase = runtimeScript
  ? runtimeScript.src.replace(/\/runtime\.js(?:\?.*)?$/, '')
  : import.meta.url.slice(0, import.meta.url.lastIndexOf('/'))
const vditorCdn = `${runtimeBase}/vditor`

export async function renderMarkdown(
  element: HTMLElement,
  source: string | null | undefined,
  theme: 'light' | 'dark',
): Promise<void> {
  await renderToElement(element, source ?? '', createPreviewOptions({ cdn: vditorCdn, theme }))
}
