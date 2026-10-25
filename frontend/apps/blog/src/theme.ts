import starterCss from '@simple-notes/theme-starter/theme.css?raw'
import starterHtml from '@simple-notes/theme-starter/theme.html?raw'

import type { ThemePayload, ThemeValidationResult } from './types'

/** 运行时契约版本（docs/blog-runtime-theme.md §3.1）：与后端 ThemeService 同步。 */
export const RUNTIME_CONTRACT = 1

/** 内置默认主题：runtime 兜底 + 主题契约不兼容时的回退（随构建发布）。 */
export const starterTheme: ThemePayload = {
  id: 'default',
  versionNo: 0,
  contract: 1,
  html: starterHtml,
  css: starterCss,
}

const MAX_HTML_BYTES = 64 * 1024
const MAX_CSS_BYTES = 256 * 1024

const FORBIDDEN: ReadonlyArray<readonly [string, string]> = [
  ['<script', '禁止 <script>'],
  ['v-html', '禁止 v-html'],
  ['javascript:', '禁止 javascript: 协议'],
  ['window.', '禁止访问 window'],
  ['document.', '禁止访问 document'],
  ['localstorage', '禁止访问 localStorage'],
  ['fetch(', '禁止调用 fetch'],
  ['eval(', '禁止调用 eval'],
  ['import(', '禁止动态 import'],
]

/**
 * 主题静态校验（docs/blog-runtime-theme.md §8）。
 *
 * 保存时后端已校验；运行时加载前再校验一次，避免库内旧数据或被篡改的数据直接进模板编译器。
 */
export function validateTheme(html: string, css: string): ThemeValidationResult {
  const errors: Array<{ line: number; message: string }> = []
  const source = String(html || '')
  const lower = source.toLowerCase()

  function lineOf(index: number): number {
    return index < 0 ? 0 : source.slice(0, index).split('\n').length
  }

  if (!lower.includes('id="app"') && !lower.includes("id='app'")) {
    errors.push({ line: 0, message: '缺少 id="app" 根节点' })
  }

  for (const [pattern, message] of FORBIDDEN) {
    const index = lower.indexOf(pattern)
    if (index >= 0) {
      errors.push({ line: lineOf(index), message })
    }
  }

  if (new Blob([source]).size > MAX_HTML_BYTES) {
    errors.push({ line: 0, message: '主题 HTML 超出 64KB 上限' })
  }

  const style = String(css || '')
  if (new Blob([style]).size > MAX_CSS_BYTES) {
    errors.push({ line: 0, message: '主题 CSS 超出 256KB 上限' })
  }
  if (/@import/i.test(style)) {
    errors.push({ line: 0, message: '主题 CSS 禁止 @import' })
  }

  return { ok: errors.length === 0, errors }
}

/** 主题 CSS 必须在首帧生效：开发路径由运行时写入，生产路径由后端内联。 */
export function applyThemeCss(css: string): void {
  let element = document.getElementById('blog-theme') as HTMLStyleElement | null
  if (!element) {
    element = document.createElement('style')
    element.id = 'blog-theme'
    document.head.appendChild(element)
  }
  element.textContent = css
}

/** 开发路径：把主题 HTML 注入 body（与生产壳的 DOM 形状保持一致）。 */
export function injectThemeHtml(html: string): HTMLElement | null {
  document.getElementById('blog-dev-loading')?.remove()
  document.getElementById('app')?.remove()
  document.body.insertAdjacentHTML('beforeend', html)
  return document.getElementById('app')
}

export function removeCloak(): void {
  document.querySelectorAll('[v-cloak]').forEach((element) => element.removeAttribute('v-cloak'))
}
