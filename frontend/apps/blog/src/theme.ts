import starterCss from '@simple-notes/theme-starter/theme.css?raw'
import starterHtml from '@simple-notes/theme-starter/theme.html?raw'

import { CONTRACT_ACTIONS, CONTRACT_COMPONENTS, CONTRACT_CSS_VARS, CONTRACT_DATA, CONTRACT_DIRECTIVES, CONTRACT_EXPRESSION, CONTRACT_HELPERS } from './contract'
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
const EXPRESSION_GLOBALS = new Set<string>(CONTRACT_EXPRESSION.globals)
const NATIVE_EVENTS = new Set<string>(CONTRACT_EXPRESSION.nativeEvents)
const BUILTIN_COMPONENTS = new Set<string>(CONTRACT_EXPRESSION.builtinComponents)
const DENIED_BUILTIN_COMPONENTS = new Set<string>(CONTRACT_EXPRESSION.deniedBuiltinComponents)

function kebabCase(name: string): string {
  return name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
}

/** 已知内置组件里，除 allow 之外一律拒绝（同时覆盖 PascalCase 与 kebab-case）。 */
function isDeniedBuiltin(tag: string): boolean {
  const lower = tag.toLowerCase()
  for (const allowed of BUILTIN_COMPONENTS) {
    if (lower === allowed.toLowerCase() || lower === kebabCase(allowed)) return false
  }
  for (const denied of DENIED_BUILTIN_COMPONENTS) {
    if (lower === denied.toLowerCase() || lower === kebabCase(denied)) return true
  }
  return false
}
const EVENT_LOCAL_SHAPES: readonly string[] = CONTRACT_EXPRESSION.eventLocalShapes
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

/** 顶层分隔（忽略字符串与括号内部），用于解构模式。 */
function splitTopLevel(text: string, delimiter = ','): string[] {
  const parts: string[] = []
  let start = 0
  let depth = 0
  let quote: string | null = null
  let escape = false
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]
    if (quote) {
      if (escape) {
        escape = false
      } else if (char === '\\') {
        escape = true
      } else if (char === quote) {
        quote = null
      }
      continue
    }
    if (char === "'" || char === '"' || char === '`') {
      quote = char
      continue
    }
    if (char === '(' || char === '[' || char === '{') {
      depth += 1
    } else if (char === ')' || char === ']' || char === '}') {
      depth = Math.max(0, depth - 1)
    } else if (char === delimiter && depth === 0) {
      parts.push(text.slice(start, index))
      start = index + 1
    }
  }
  parts.push(text.slice(start))
  return parts.filter((part) => part.trim())
}

/** 找到顶层目标字符（忽略字符串与括号内部）。 */
function findTopLevel(text: string, target: string): number {
  let depth = 0
  let quote: string | null = null
  let escape = false
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]
    if (quote) {
      if (escape) {
        escape = false
      } else if (char === '\\') {
        escape = true
      } else if (char === quote) {
        quote = null
      }
      continue
    }
    if (char === "'" || char === '"' || char === '`') {
      quote = char
      continue
    }
    if (char === '(' || char === '[' || char === '{') {
      depth += 1
    } else if (char === ')' || char === ']' || char === '}') {
      depth = Math.max(0, depth - 1)
    } else if (char === target && depth === 0) {
      return index
    }
  }
  return -1
}

/** 去掉最外层包裹的圆括号（`(item, index)` → `item, index`）。 */
function stripOuterParens(text: string): string {
  let value = text.trim()
  while (value.startsWith('(') && value.endsWith(')')) {
    let depth = 0
    let wraps = true
    for (let index = 0; index < value.length; index += 1) {
      const char = value[index]
      if (char === '(') depth += 1
      else if (char === ')') {
        depth -= 1
        if (depth === 0 && index < value.length - 1) {
          wraps = false
          break
        }
      }
    }
    if (!wraps || depth !== 0) break
    value = value.slice(1, -1).trim()
  }
  return value
}

function addBindingName(name: string, names: Set<string>): void {
  const value = name.trim()
  if (/^[A-Za-z_$][\w$]*$/.test(value)) names.add(value)
}

/** 解构模式中真正被绑定的变量名（`{ title, slug: s, ...rest }` → title / s / rest）。 */
function collectBindingNames(pattern: string, names = new Set<string>()): Set<string> {
  const text = stripOuterParens(pattern)
  if (!text) return names
  const topLevel = splitTopLevel(text)
  if (topLevel.length > 1) {
    for (const part of topLevel) collectBindingNames(part, names)
    return names
  }
  if (text.startsWith('{') && text.endsWith('}')) {
    for (const part of splitTopLevel(text.slice(1, -1))) {
      const item = part.trim()
      if (!item) continue
      if (item.startsWith('...')) {
        addBindingName(item.slice(3), names)
        continue
      }
      const colon = findTopLevel(item, ':')
      if (colon >= 0) {
        collectBindingNames(item.slice(colon + 1), names)
        continue
      }
      const equals = findTopLevel(item, '=')
      collectBindingNames(equals >= 0 ? item.slice(0, equals) : item, names)
    }
    return names
  }
  if (text.startsWith('[') && text.endsWith(']')) {
    for (const part of splitTopLevel(text.slice(1, -1))) {
      const item = part.trim()
      if (!item) continue
      if (item.startsWith('...')) {
        addBindingName(item.slice(3), names)
        continue
      }
      const equals = findTopLevel(item, '=')
      collectBindingNames(equals >= 0 ? item.slice(0, equals) : item, names)
    }
    return names
  }
  addBindingName(text, names)
  return names
}

/** slot 作用域声明的字段名（`{ post: p }` → post；`{ ...rest }` 不产生字段）。 */
function slotFieldNames(pattern: string): Set<string> {
  const fields = new Set<string>()
  const text = stripOuterParens(pattern)
  if (!text.startsWith('{') || !text.endsWith('}')) return fields
  for (const part of splitTopLevel(text.slice(1, -1))) {
    const item = part.trim()
    if (!item || item.startsWith('...')) continue
    const colon = findTopLevel(item, ':')
    let left = colon >= 0 ? item.slice(0, colon) : item
    const equals = findTopLevel(left, '=')
    if (equals >= 0) left = left.slice(0, equals)
    left = left.trim()
    if (/^[A-Za-z_$][\w$]*$/.test(left)) fields.add(left)
  }
  return fields
}

/** `$event` 只允许窄形状；返回清理后的表达式与错误消息。 */
function checkEventLocals(expression: string): { text: string; errors: string[] } {
  const errors: string[] = []
  for (const match of expression.matchAll(/\$event/g)) {
    const rest = expression.slice(match.index + '$event'.length)
    const allowed = EVENT_LOCAL_SHAPES.some((shape) => {
      const prefix = `.${shape}`
      if (!rest.startsWith(prefix)) return false
      const after = rest.slice(prefix.length)
      return !after || !/^[\w$]/.test(after)
    })
    if (!allowed) {
      errors.push(
        '不允许的 $event 用法（仅允许 $event.target.value / $event.target.checked / ' +
          '$event.key / $event.code / $event.preventDefault() / $event.stopPropagation()）',
      )
    }
  }
  return { text: expression.replace(/\$event/g, ' '), errors }
}

/** v-for / slot 作用域的局部变量：表达式里出现它们不算未登记标识符。 */
function collectLocals(html: string): Set<string> {
  const locals = new Set<string>()
  for (const match of html.matchAll(/\bv-for="([\s\S]*?)\s+(?:in|of)\s+[^"]*"/g)) {
    collectBindingNames(match[1], locals)
  }
  for (const match of html.matchAll(/(?:#[\w-]*|v-slot(?::[\w-]+)?)="([^"]*)"/g)) {
    collectBindingNames(match[1], locals)
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
  for (const match of html.matchAll(/<\s*([A-Za-z][A-Za-z0-9-]*)/g)) {
    if (isDeniedBuiltin(match[1])) {
      errors.push({ line: lineAt(match.index), message: `使用了未登记的内置组件 <${match[1]}>` })
    }
  }
  for (const match of html.matchAll(/\s(v-[a-z-]+)/g)) {
    if (!DIRECTIVE_NAMES.has(match[1])) {
      errors.push({ line: lineAt(match.index), message: `使用了未登记的指令 ${match[1]}` })
    }
  }

  // slot 名与作用域字段（union 口径：不区分具体组件，与后端同口径）
  const slotNames = new Set<string>(
    Object.values(CONTRACT_COMPONENTS).flatMap((entry) => entry.slots.map((slot) => slot.name)),
  )
  const slotScope = new Map<string, Set<string>>()
  for (const entry of Object.values(CONTRACT_COMPONENTS)) {
    for (const slot of entry.slots) {
      if (!slotScope.has(slot.name)) slotScope.set(slot.name, new Set())
      slot.provides.forEach((field) => slotScope.get(slot.name)!.add(field))
    }
  }
  for (const match of html.matchAll(/(?:#([\w-]+)|v-slot(?::([\w-]+))?)\s*=\s*"([^"]*)"/g)) {
    const name = match[1] ?? match[2] ?? 'default'
    if (!slotNames.has(name)) {
      errors.push({ line: lineAt(match.index), message: `使用了未登记的 slot #${name}` })
      continue
    }
    for (const field of slotFieldNames(match[3])) {
      if (!slotScope.get(name)?.has(field)) {
        errors.push({
          line: lineAt(match.index),
          message: `slot #${name} 引用了未登记的作用域字段 "${field}"`,
        })
      }
    }
  }

  // 必填 props 与组件事件；引号内的 `>` 不能截断标签
  for (const match of html.matchAll(/<\s*(blog-[a-z-]+)((?:[^>"']|"[^"]*"|'[^']*')*)>/g)) {
    const tag = match[1]
    const attrs = match[2]
    const entry = (CONTRACT_COMPONENTS as Record<string, {
      requiredProps: readonly string[]
      events: readonly string[]
    }>)[tag]
    if (!entry) continue
    for (const prop of entry.requiredProps) {
      const pattern = new RegExp(`(?<![\\w-])(?:(?::|v-bind:)${prop}(?=[\\s=/>])|${prop}\\s*=)`)
      if (!pattern.test(attrs)) {
        errors.push({ line: lineAt(match.index), message: `<${tag}> 缺少必填 prop "${prop}"` })
      }
    }
    for (const event of attrs.matchAll(/(?:^|\s)(?:@|v-on:)([A-Za-z][\w:-]*)(?:\.[\w-]+)*/g)) {
      if (!entry.events.includes(event[1]) && !NATIVE_EVENTS.has(event[1])) {
        errors.push({
          line: lineAt(match.index),
          message: `<${tag}> 使用了未登记事件 "${event[1]}"`,
        })
      }
    }
  }

  const expressions: Array<{ text: string; index: number; event: boolean }> = []
  for (const match of html.matchAll(/{{([\s\S]*?)}}/g)) {
    expressions.push({ text: match[1], index: match.index, event: false })
  }
  for (const match of html.matchAll(/\s([:@][A-Za-z0-9_.:[\]-]+|v-(?:bind|on|if|else-if|for|show|model|text|once)[A-Za-z0-9_.:[\]-]*)="([^"]*)"/g)) {
    const attr = match[1]
    expressions.push({
      text: match[2],
      index: match.index,
      event: attr.startsWith('@') || attr === 'v-on' || attr.startsWith('v-on:'),
    })
  }
  for (const expression of expressions) {
    let text = expression.text
    if (expression.event) {
      const checked = checkEventLocals(text)
      text = checked.text
      for (const message of checked.errors) {
        errors.push({ line: lineAt(expression.index), message })
      }
    } else if (text.includes('$event')) {
      errors.push({
        line: lineAt(expression.index),
        message: '$event 只能用于事件表达式（@ / v-on）',
      })
      text = text.replace(/\$event/g, ' ')
    }
    for (const name of freeIdentifiers(text)) {
      if (
        JS_KEYWORDS.has(name) ||
        locals.has(name) ||
        TEMPLATE_KEYS.has(name) ||
        EXPRESSION_GLOBALS.has(name)
      ) {
        continue
      }
      errors.push({ line: lineAt(expression.index), message: `引用了未登记的标识符 "${name}"` })
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

const FORBIDDEN_LOWER: ReadonlyArray<readonly [string, string]> = [
  ['<script', '禁止 <script>'],
  ['v-html', '禁止 v-html'],
  ['javascript:', '禁止 javascript: 协议'],
]
/** JS 语法大小写敏感：用精确匹配，避免 ownerDocument. 误伤 document. */
const FORBIDDEN_EXACT: ReadonlyArray<readonly [string, string]> = [
  ['window.', '禁止访问 window'],
  ['document.', '禁止访问 document'],
  ['localStorage', '禁止访问 localStorage'],
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

  if (!/(?<![\w:-])id\s*=\s*(?:"app"|'app'|app)(?=[\s/>])/i.test(source)) {
    errors.push({ line: 0, message: '缺少 id="app" 根节点' })
  }

  for (const [pattern, message] of FORBIDDEN_LOWER) {
    const index = lower.indexOf(pattern)
    if (index >= 0) {
      errors.push({ line: lineOf(index), message })
    }
  }
  for (const [pattern, message] of FORBIDDEN_EXACT) {
    const index = source.indexOf(pattern)
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
