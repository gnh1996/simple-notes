import starterCss from '@simple-notes/theme-starter/theme.css?raw'
import starterHtml from '@simple-notes/theme-starter/theme.html?raw'

import { CONTRACT_ACTIONS, CONTRACT_COMPONENTS, CONTRACT_CSS_VARS, CONTRACT_DATA, CONTRACT_DIRECTIVES, CONTRACT_HELPERS } from './contract'
import type { ThemePayload, ThemeValidationResult } from './types'

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

const TEMPLATE_KEYS = new Set([
  ...Object.keys(CONTRACT_DATA),
  ...Object.keys(CONTRACT_HELPERS),
  ...Object.keys(CONTRACT_ACTIONS),
])
const COMPONENT_NAMES = new Set(Object.keys(CONTRACT_COMPONENTS))
const DIRECTIVE_NAMES = new Set<string>(CONTRACT_DIRECTIVES)
const JS_KEYWORDS = new Set([
  'true', 'false', 'null', 'undefined', 'in', 'of', 'new', 'typeof', 'instanceof',
  'void', 'delete', 'return', 'if', 'else', 'this', 'NaN', 'Infinity',
])

/**
 * HTML 实体归一（docs/blog-runtime-theme.md §8）：解码回原文字符。
 *
 * 加载时校验吃的是 `app.outerHTML`（浏览器序列化产物）：`&&` 会被转写为 `&amp;&amp;`、
 * `<` / `>` / `"` 转写为 `&lt;` / `&gt;` / `&quot;`（连箭头函数 `=>` 都会变成 `=&gt;`）。
 * 直接做标识符扫描会把 amp/lt/gt 误判为未登记标识符、把箭头参数冲掉，导致回落默认主题。
 * 因此先按序列化逆操作解码（`&amp;` 必须最后，避免 `&amp;lt;` 被级联解码两次），
 * 解码后再走同一套扫描，三处口径（保存 / 预览 / 加载）一致。
 * 只处理序列化会产生的六种实体；其他编码形式（如 &#58;）不在此列。
 */
function normalizeEntities(source: string): string {
  return source
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&#x27;/gi, "'")
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
}

/** v-for / slot 作用域的局部变量：表达式里出现它们不算未登记标识符。 */
function collectLocals(html: string): Set<string> {
  const locals = new Set<string>()
  for (const match of html.matchAll(/v-for="\(?([^)"]*?)\)?\s+(?:in|of)\s+/g)) {
    for (const part of match[1].split(',')) {
      const name = part.trim()
      if (name) locals.add(name)
    }
  }
  for (const match of html.matchAll(/(?:#[\w-]*|v-slot(?::[\w-]+)?)="([^"]*)"/g)) {
    const value = match[1].trim()
    const inner = value.startsWith('{') ? value.slice(1, value.lastIndexOf('}')) : value
    for (const part of inner.split(',')) {
      const pieces = part.split(':')
      const name = (pieces.length > 1 ? pieces[1] : pieces[0]).trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) locals.add(name)
    }
  }
  return locals
}

function arrowParamNames(expression: string): Set<string> {
  const names = new Set<string>()
  for (const match of expression.matchAll(/(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g)) {
    for (const part of (match[1] ?? match[2] ?? '').split(',')) {
      let name = part.split('=')[0].trim().replace(/^\.\.\./, '')
      if (name.includes(':')) name = name.split(':').pop()!.trim()
      name = name.replace(/[{}\[\]]/g, '').trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) names.add(name)
    }
  }
  return names
}

function stripArrowParams(expression: string): string {
  return expression.replace(
    /(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g,
    (match) => ' '.repeat(match.length),
  )
}

/** 提取表达式中的自由标识符（字符串、属性访问、对象字面量键、箭头参数除外）。 */
function freeIdentifiers(expression: string): string[] {
  const arrowParams = arrowParamNames(expression)
  const cleaned = stripArrowParams(
    expression.replace(/'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`/g, ' '),
  )
  const names: string[] = []
  const pattern = /(?<![\w$.])([A-Za-z_$][\w$]*)/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(cleaned))) {
    const name = match[1]
    if (arrowParams.has(name)) continue
    const before = cleaned.slice(0, match.index).trimEnd().slice(-1)
    const after = cleaned.slice(pattern.lastIndex).match(/^\s*:/)
    if (after && (before === '{' || before === ',')) continue
    names.push(name)
  }
  return names
}

function scanTheme(html: string, errors: Array<{ line: number; message: string }>): void {
  const lineAt = (index: number): number => (index < 0 ? 0 : html.slice(0, index).split('\n').length)
  const locals = collectLocals(html)

  for (const match of html.matchAll(/<\s*(blog-[a-z-]+)/g)) {
    if (!COMPONENT_NAMES.has(match[1])) {
      errors.push({ line: lineAt(match.index), message: `使用了未登记的组件 <${match[1]}>` })
    }
  }
  for (const match of html.matchAll(/\s(v-[a-z-]+)/g)) {
    if (!DIRECTIVE_NAMES.has(match[1])) {
      errors.push({ line: lineAt(match.index), message: `使用了未登记的指令 ${match[1]}` })
    }
  }

  const expressions: Array<{ text: string; index: number }> = []
  for (const match of html.matchAll(/{{([\s\S]*?)}}/g)) {
    expressions.push({ text: match[1], index: match.index })
  }
  for (const match of html.matchAll(/\s([:@][A-Za-z0-9_.:[\]-]+|v-(?:bind|on|if|else-if|for|show|model|text|once)[A-Za-z0-9_.:[\]-]*)="([^"]*)"/g)) {
    expressions.push({ text: match[2], index: match.index })
  }
  for (const { text, index } of expressions) {
    for (const name of freeIdentifiers(text)) {
      if (JS_KEYWORDS.has(name) || locals.has(name) || TEMPLATE_KEYS.has(name)) continue
      errors.push({ line: lineAt(index), message: `引用了未登记的标识符 "${name}"` })
    }
  }
}

function scanCssUrls(css: string, errors: Array<{ line: number; message: string }>): void {
  const lineAt = (index: number): number => (index < 0 ? 0 : css.slice(0, index).split('\n').length)
  for (const match of css.matchAll(/url\(\s*([^)]*)\s*\)/gi)) {
    const target = match[1].trim().replace(/^["']|["']$/g, '')
    if (!target.startsWith('/api/media/') && !target.startsWith('data:')) {
      errors.push({ line: lineAt(match.index), message: 'CSS url() 仅允许 /api/media/ 与 data: 资源' })
    }
  }
}

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
  // 先做实体归一（见 SERIALIZED_ENTITY_PATTERN）：加载时吃的是 outerHTML，
  // 保存 / 预览校验吃的是原文；归一后三处口径一致。
  const source = normalizeEntities(String(html || ''))
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

  scanTheme(source, errors)

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
  scanCssUrls(style, errors)

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

/** 应用站点设置里的 CSS 变量覆盖；只接受契约登记的变量名。 */
export function applyThemeOverrides(overrides: Record<string, string> | null | undefined): void {
  const root = document.documentElement
  for (const key of CONTRACT_CSS_VARS) {
    root.style.removeProperty(key)
  }
  if (!overrides) {
    return
  }
  for (const [key, value] of Object.entries(overrides)) {
    if (!CONTRACT_CSS_VARS.includes(key as (typeof CONTRACT_CSS_VARS)[number])) {
      continue
    }
    if (typeof value !== 'string' || !value.trim()) {
      continue
    }
    root.style.setProperty(key, value.trim())
  }
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
