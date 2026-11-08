#!/usr/bin/env node
/**
 * 主题契约自检（docs/blog-runtime-theme.md §3）。
 *
 * 守护四件事，任一条不满足即失败：
 *   1. runtime 暴露给模板的成员 == contract.ts 登记的数据 / 辅助函数 / 动作
 *   2. 组件注册表 == contract.ts 登记的组件
 *   3. runtime.css 消费的 --blog-* 变量都已在契约中登记
 *   4. 默认主题（starter）只使用已登记的：组件、指令、标识符
 *
 * 用法：
 *   node scripts/theme-contract.mjs                 # 校验（CI / typecheck 内置）
 *   node scripts/theme-contract.mjs --emit          # 额外生成 apps/blog/dist/theme-contract.json
 *   node scripts/theme-contract.mjs --theme <file>  # 额外校验任意主题 HTML（调试用，如库内主题）
 */

import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'
import { parse as parseSfc } from 'vue/compiler-sfc'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const repoRoot = path.resolve(root, '..')
const blogSrc = path.join(root, 'apps/blog/src')
const starterHtml = path.join(root, 'packages/theme-starter/theme.html')

const contract = await import(pathToFileURL(path.join(blogSrc, 'contract.ts')).href)
const {
  CONTRACT_DATA,
  CONTRACT_HELPERS,
  CONTRACT_ACTIONS,
  CONTRACT_COMPONENTS,
  CONTRACT_ROUTES,
  CONTRACT_URLS,
} = contract
const CONTRACT_CSS_VARS = new Set(contract.CONTRACT_CSS_VARS)
const CONTRACT_DIRECTIVES = new Set(contract.CONTRACT_DIRECTIVES)
const CONTRACT_COMPONENT_NAMES = new Set(Object.keys(CONTRACT_COMPONENTS))
const TEMPLATE_KEYS = new Set([
  ...Object.keys(CONTRACT_DATA),
  ...Object.keys(CONTRACT_HELPERS),
  ...Object.keys(CONTRACT_ACTIONS),
])

const errors = []

// ---------------------------------------------------------------------------
// 0. 契约自身一致性：requiredProps ⊆ props；slot.provides == slot.scope.name
// ---------------------------------------------------------------------------
for (const [key, entry] of Object.entries(CONTRACT_COMPONENTS)) {
  const props = new Set(entry.props)
  for (const required of entry.requiredProps ?? []) {
    if (!props.has(required)) {
      errors.push(`组件 ${key} 的 requiredProps 含未登记 props：${required}`)
    }
  }
  for (const slot of entry.slots ?? []) {
    const scopeNames = (slot.scope ?? []).map((field) => field.name)
    if (scopeNames.join('|') !== slot.provides.join('|')) {
      errors.push(`组件 ${key} 的 slot #${slot.name}：provides 与 scope 字段不一致`)
    }
  }
}

/** 相对仓库根目录的路径，便于报错定位 */
function rel(file) {
  return path.relative(root, file).replace(/\\/g, '/')
}

function lineAt(text, index) {
  return text.slice(0, index).split('\n').length
}

async function readText(file) {
  return readFile(file, 'utf8')
}

async function readSource(file) {
  const text = await readText(file)
  return ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
}

function walk(node, visit) {
  visit(node)
  node.forEachChild((child) => walk(child, visit))
}

function propertyName(node) {
  if (!node.name) {
    return null
  }
  if (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name)) {
    return node.name.text
  }
  return null
}

/** 返回函数内第一个 `return { ... }` 的键集合 */
function returnObjectKeys(source, functionName) {
  let keys = null
  walk(source, (node) => {
    if (keys || !ts.isFunctionDeclaration(node) || node.name?.text !== functionName || !node.body) {
      return
    }
    walk(node.body, (inner) => {
      if (keys || !ts.isReturnStatement(inner) || !inner.expression) {
        return
      }
      if (ts.isObjectLiteralExpression(inner.expression)) {
        keys = inner.expression.properties
          .map((property) => propertyName(property))
          .filter((name) => name !== null)
      }
    })
  })
  if (!keys) {
    errors.push(`无法解析 ${functionName}() 的返回对象（脚本需要随实现更新）`)
    return []
  }
  return keys
}

/** 返回 `export const <name> = { ... }` 的键集合 */
function objectLiteralKeys(source, variableName) {
  let keys = null
  walk(source, (node) => {
    if (keys || !ts.isVariableStatement(node)) {
      return
    }
    for (const declaration of node.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name) || declaration.name.text !== variableName) {
        continue
      }
      if (declaration.initializer && ts.isObjectLiteralExpression(declaration.initializer)) {
        keys = declaration.initializer.properties
          .map((property) => propertyName(property))
          .filter((name) => name !== null)
      }
    }
  })
  if (!keys) {
    errors.push(`无法解析 ${variableName} 的对象字面量（脚本需要随实现更新）`)
    return []
  }
  return keys
}

function diffSets(label, actual, expected, where) {
  const missing = [...expected].filter((name) => !actual.has(name))
  const extra = [...actual].filter((name) => !expected.has(name))
  if (missing.length) {
    errors.push(`${label}：已登记但未暴露 → ${missing.join(', ')}（${where}）`)
  }
  if (extra.length) {
    errors.push(`${label}：已暴露但未登记 → ${extra.join(', ')}（请在 contract.ts 补登记）`)
  }
}

// ---------------------------------------------------------------------------
// 1. 模板上下文 == 契约
// ---------------------------------------------------------------------------

const contextSource = await readSource(path.join(blogSrc, 'template-context.ts'))
const exposed = new Set(returnObjectKeys(contextSource, 'createTemplateContext'))
diffSets('模板上下文', exposed, TEMPLATE_KEYS, 'apps/blog/src/template-context.ts')

// ---------------------------------------------------------------------------
// 2. 组件注册表 == 契约（一个组件一个文件，注册表在 components/index.ts）
// ---------------------------------------------------------------------------

const componentsDir = path.join(blogSrc, 'components')
const registryFile = path.join(componentsDir, 'index.ts')
const registrySource = await readSource(registryFile)
const registered = new Set(objectLiteralKeys(registrySource, 'blogComponents'))
diffSets('组件注册表', registered, CONTRACT_COMPONENT_NAMES, 'apps/blog/src/components/index.ts')

// ---------------------------------------------------------------------------
// 3. 组件样式接口（parts）：组件实际输出的 class == 登记的可覆盖 class
// ---------------------------------------------------------------------------

/** `components/index.ts` 的 `blogComponents`：契约名 → 组件标识符 */
function componentRegistry(source) {
  const registry = new Map()

  walk(source, (node) => {
    if (registry.size || !ts.isVariableStatement(node)) {
      return
    }
    for (const declaration of node.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name) || declaration.name.text !== 'blogComponents') {
        continue
      }
      if (declaration.initializer && ts.isObjectLiteralExpression(declaration.initializer)) {
        for (const property of declaration.initializer.properties) {
          const key = propertyName(property)
          if (key && property.initializer && ts.isIdentifier(property.initializer)) {
            registry.set(key, property.initializer.text)
          }
        }
      }
    }
  })

  return registry
}

function emittedParts(template) {
  return new Set(
    [...template.matchAll(/(?<!:)class="([^"]*)"/g)]
      .flatMap((match) => match[1].split(/\s+/))
      .filter(Boolean),
  )
}

/** 组件模板里声明的 slot：`<slot>` = default，`<slot name="x" :post="post">` 记 name + 作用域变量 */
function emittedSlots(template) {
  const slots = new Map()
  for (const match of template.matchAll(/<slot\b([^>]*)>/g)) {
    const attributes = match[1]
    const nameMatch = attributes.match(/(?<![:#\w-])name="([^"]+)"/)
    const name = nameMatch ? nameMatch[1] : 'default'
    const provides = [...attributes.matchAll(/(?<![\w-])(?::|v-bind:)([A-Za-z_$][\w$]*)=/g)].map(
      (binding) => binding[1],
    )
    slots.set(name, new Set(provides))
  }
  return slots
}

const registry = componentRegistry(registrySource)
const publicParts = new Set(contract.CONTRACT_GLOBAL_PARTS)

/** components/index.ts 的相对导入：局部名 → 说明符（含 `import type` 之外的具名 / 默认导入） */
function componentImports(source) {
  const imports = new Map()
  walk(source, (node) => {
    if (!ts.isImportDeclaration(node) || !node.moduleSpecifier) {
      return
    }
    const specifier = ts.isStringLiteral(node.moduleSpecifier) ? node.moduleSpecifier.text : ''
    if (!specifier.startsWith('.') || !node.importClause) {
      return
    }
    if (node.importClause.name) {
      imports.set(node.importClause.name.text, specifier)
    }
    const bindings = node.importClause.namedBindings
    if (bindings && ts.isNamedImports(bindings)) {
      for (const element of bindings.elements) {
        imports.set(element.name.text, specifier)
      }
    }
  })
  return imports
}

/**
 * 单文件组件的 template：SFC 的 `<style>` 一律不允许（`.bc-*` 是公开样式接口，
 * 组件样式只能留在 runtime.css，否则主题按 parts 覆盖会静默失效）。
 */
function vueTemplate(file, text) {
  const { descriptor, errors: sfcErrors } = parseSfc(text, { filename: file })
  if (sfcErrors.length) {
    errors.push(`${rel(file)} 解析失败 → ${sfcErrors[0].message}`)
    return null
  }
  if (descriptor.styles.length) {
    errors.push(`${rel(file)} 含 <style> 块：组件样式必须写在 runtime.css（.bc-* 是公开样式接口）`)
  }
  if (!descriptor.template) {
    errors.push(`${rel(file)} 缺少 <template>（组件模板是样式接口校验的来源）`)
    return null
  }
  return descriptor.template.content
}

const componentFiles = (await readdir(componentsDir, { withFileTypes: true }))
  .filter((entry) => entry.isFile() && entry.name !== 'index.ts')
  .map((entry) => path.join(componentsDir, entry.name))
  .sort()

const knownFiles = new Set(componentFiles)

/** `./pager` → ./pager.vue | ./pager/index.vue；带扩展名时按原样 */
function resolveComponentFile(specifier) {
  const base = path.resolve(componentsDir, specifier)
  return [base, `${base}.vue`, path.join(base, 'index.vue')].find((candidate) => knownFiles.has(candidate))
}

const imports = componentImports(registrySource)
const templates = new Map()
const templateOwners = new Map()
const usedFiles = new Set()

for (const [key, identifier] of registry) {
  const specifier = imports.get(identifier)
  const file = specifier ? resolveComponentFile(specifier) : undefined
  if (!file) {
    errors.push(`无法定位组件 ${key} 的文件（index.ts 需从 components/ 内导入 ${identifier}）`)
    continue
  }
  usedFiles.add(file)
  const owner = rel(file)
  if (!file.endsWith('.vue')) {
    errors.push(`${owner} 不是 .vue：blog 组件统一用单文件组件（组件内禁止 <style>，样式留 runtime.css）`)
    continue
  }
  const template = vueTemplate(file, await readText(file))
  if (!template) {
    continue
  }
  templates.set(key, template)
  templateOwners.set(key, owner)
}

for (const file of componentFiles) {
  if (!usedFiles.has(file)) {
    errors.push(`${rel(file)} 未在 components/index.ts 注册（组件文件必须挂到注册表，否则主题用不到）`)
  }
}

for (const [key, entry] of Object.entries(CONTRACT_COMPONENTS)) {
  entry.parts.forEach((part) => publicParts.add(part))
  const template = templates.get(key)
  const where = templateOwners.get(key) ?? 'apps/blog/src/components/index.ts'
  if (!template) {
    errors.push(`无法解析组件 ${key} 的模板（脚本需要随实现更新）`)
    continue
  }
  const declaredParts = new Set(entry.parts)
  const actualParts = emittedParts(template)
  diffSets(`组件 ${key} 的 parts`, actualParts, declaredParts, where)

  const actualSlots = emittedSlots(template)
  const declaredSlots = new Map(entry.slots.map((slot) => [slot.name, new Set(slot.provides)]))
  diffSets(`组件 ${key} 的 slots`, new Set(actualSlots.keys()), new Set(declaredSlots.keys()), where)
  for (const [name, provided] of declaredSlots) {
    const emitted = actualSlots.get(name)
    if (!emitted) {
      continue
    }
    diffSets(`组件 ${key} 的 slot #${name} 作用域`, emitted, provided, where)
  }
}

// ---------------------------------------------------------------------------
// 4. 路由表：名字不得重复（vue-router 5 会丢弃先注册的同名路由），
//    且契约里引用的路由名必须真实存在
// ---------------------------------------------------------------------------

const runtimeSource = await readSource(path.join(blogSrc, 'runtime.ts'))
const routeNames = []
const routePaths = new Map()
walk(runtimeSource, (node) => {
  if (!ts.isObjectLiteralExpression(node)) {
    return
  }
  const nameProperty = node.properties.find(
    (property) => propertyName(property) === 'name' && property.initializer && ts.isStringLiteral(property.initializer),
  )
  const pathProperty = node.properties.find((property) => propertyName(property) === 'path')
  if (nameProperty && pathProperty) {
    const name = nameProperty.initializer.text
    routeNames.push(name)
    if (ts.isStringLiteral(pathProperty.initializer)) {
      routePaths.set(name, pathProperty.initializer.text)
    }
  }
})

const duplicatedRoutes = routeNames.filter((name, index) => routeNames.indexOf(name) !== index)
if (duplicatedRoutes.length) {
  errors.push(`路由名重复 → ${[...new Set(duplicatedRoutes)].join(', ')}（vue-router 5 会丢弃先注册的那条）`)
}

const referencedRoutes = new Set(
  Object.values(CONTRACT_DATA).flatMap((entry) => (entry.routes === 'all' ? [] : entry.routes)),
)
for (const name of referencedRoutes) {
  if (!routeNames.includes(name)) {
    errors.push(`契约引用了不存在的路由名 "${name}"（apps/blog/src/runtime.ts）`)
  }
}

// CONTRACT_ROUTES 必须与 runtime.ts 双向一致（名字 + path）
for (const [name, def] of Object.entries(CONTRACT_ROUTES)) {
  if (!routeNames.includes(name)) {
    errors.push(`CONTRACT_ROUTES 登记了 runtime 不存在的路由 "${name}"`)
    continue
  }
  const path = routePaths.get(name)
  if (path && path !== def.path) {
    errors.push(`路由 "${name}" 的 path 不一致：runtime=${path} contract=${def.path}`)
  }
}
for (const name of routeNames) {
  if (!CONTRACT_ROUTES[name]) {
    errors.push(`runtime 路由 "${name}" 未登记到 CONTRACT_ROUTES`)
  }
}
for (const [name, url] of Object.entries(CONTRACT_URLS)) {
  if (!CONTRACT_ROUTES[name] && name !== 'tag') {
    errors.push(`CONTRACT_URLS 引用了未知路由 "${name}"（${url}）`)
  }
}

// ---------------------------------------------------------------------------
// 4.1 类型投影：CONTRACT_DATA.shape / slot.scope.shape → types.ts 字段
// ---------------------------------------------------------------------------
const typesSource = await readSource(path.join(blogSrc, 'types.ts'))
const typeInterfaces = new Map()
const typeAliases = new Map()
walk(typesSource, (node) => {
  if (ts.isInterfaceDeclaration(node) && node.name) {
    typeInterfaces.set(node.name.text, node)
  } else if (ts.isTypeAliasDeclaration(node) && node.name) {
    typeAliases.set(node.name.text, node.type.getText(typesSource))
  }
})

const BUILTIN_TYPES = new Set([
  'Array',
  'Date',
  'Map',
  'Omit',
  'Partial',
  'Pick',
  'Promise',
  'Readonly',
  'ReadonlyArray',
  'Record',
  'Required',
  'Set',
])

function typeNames(text) {
  // 去掉字符串字面量（'displayAt' / "firstPublishedAt" 里的首字母不是类型名）
  const cleaned = String(text ?? '').replace(/'[^']*'|"[^"]*"/g, ' ')
  return [...cleaned.matchAll(/[A-Z][A-Za-z0-9_]*/g)]
    .map((match) => match[0])
    .filter((name) => !BUILTIN_TYPES.has(name))
}

function interfaceFields(node, seen = new Set()) {
  const fields = {}
  for (const clause of node.heritageClauses ?? []) {
    for (const type of clause.types) {
      const baseName = type.expression.getText(typesSource)
      if (seen.has(baseName)) {
        continue
      }
      const base = typeInterfaces.get(baseName)
      if (!base) {
        errors.push(`类型 ${node.name.text} 继承的类型无法解析：${baseName}`)
        continue
      }
      Object.assign(fields, interfaceFields(base, new Set([...seen, node.name.text])))
    }
  }
  for (const member of node.members) {
    if (!ts.isPropertySignature(member) || !member.name) {
      continue
    }
    const name = propertyName(member)
    if (!name) {
      continue
    }
    const type = member.type ? member.type.getText(typesSource) : 'unknown'
    fields[name] = member.questionToken ? `${type} | undefined` : type
  }
  return fields
}

const shapeRoots = new Set()
for (const entry of Object.values(CONTRACT_DATA)) {
  typeNames(entry.shape).forEach((name) => shapeRoots.add(name))
}
for (const entry of Object.values(CONTRACT_COMPONENTS)) {
  for (const slot of entry.slots ?? []) {
    for (const field of slot.scope ?? []) {
      typeNames(field.shape).forEach((name) => shapeRoots.add(name))
    }
  }
}

const emittedTypes = {}
const pendingTypes = [...shapeRoots]
while (pendingTypes.length) {
  const name = pendingTypes.shift()
  if (emittedTypes[name] !== undefined) {
    continue
  }
  if (typeAliases.has(name)) {
    const text = typeAliases.get(name)
    emittedTypes[name] = text
    typeNames(text).forEach((next) => pendingTypes.push(next))
    continue
  }
  const node = typeInterfaces.get(name)
  if (!node) {
    errors.push(`契约 shape 无法解析为 types.ts 类型：${name}`)
    continue
  }
  const fields = interfaceFields(node)
  emittedTypes[name] = fields
  for (const type of Object.values(fields)) {
    typeNames(type).forEach((next) => pendingTypes.push(next))
  }
}

const sortedTypes = Object.fromEntries(
  Object.keys(emittedTypes)
    .sort()
    .map((key) => [key, emittedTypes[key]]),
)
if (JSON.stringify(sortedTypes).includes('RouteLocationNormalizedLoaded')) {
  errors.push('类型投影泄漏了 vue-router 内部类型 RouteLocationNormalizedLoaded，请使用 RouteView')
}

// ---------------------------------------------------------------------------
// 5. runtime.css / 主题 CSS：可引用的组件 class 必须已登记
// ---------------------------------------------------------------------------
const runtimeCssFile = path.join(blogSrc, 'runtime.css')
const runtimeCss = await readText(runtimeCssFile)

function cssClasses(text) {
  return new Set([...text.matchAll(/\.(bc-[a-z0-9-]+)/g)].map((match) => match[1]))
}

const runtimeClasses = cssClasses(runtimeCss)
const unknownRuntimeClasses = [...runtimeClasses].filter((name) => !publicParts.has(name))
if (unknownRuntimeClasses.length) {
  errors.push(
    `runtime.css 样式化了未登记的组件 class → ${unknownRuntimeClasses.join(', ')}` +
      '（主题无法发现，请在 contract.ts 的 parts / globalParts 补登记）',
  )
}

const usedVars = new Set([...runtimeCss.matchAll(/var\(\s*(--blog-[a-z0-9-]+)/g)].map((match) => match[1]))
const unknownVars = [...usedVars].filter((name) => !CONTRACT_CSS_VARS.has(name))
if (unknownVars.length) {
  errors.push(`runtime.css 使用了未登记的变量 → ${unknownVars.join(', ')}（请在 contract.ts 补登记）`)
}

// ---------------------------------------------------------------------------
// 5. 主题 HTML 只能使用已登记的能力
// ---------------------------------------------------------------------------

const JS_KEYWORDS = new Set([
  'true', 'false', 'null', 'undefined', 'in', 'of', 'new', 'typeof', 'instanceof',
  'void', 'delete', 'return', 'if', 'else', 'this', 'NaN', 'Infinity',
])

function collectLocals(html) {
  const locals = new Set()
  for (const match of html.matchAll(/v-for="\(?([^)"]*?)\)?\s+(?:in|of)\s+/g)) {
    for (const part of match[1].split(',')) {
      const name = part.trim()
      if (name) {
        locals.add(name)
      }
    }
  }
  // slot 作用域（`#default="{ post }"` / `v-slot:default="{ post: p }"`）同样算局部变量
  for (const match of html.matchAll(/(?:#[\w-]*|v-slot(?::[\w-]+)?)="([^"]*)"/g)) {
    const value = match[1].trim()
    const inner = value.startsWith('{') ? value.slice(1, value.lastIndexOf('}')) : value
    for (const part of inner.split(',')) {
      const pieces = part.split(':')
      const name = (pieces.length > 1 ? pieces[1] : pieces[0]).trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        locals.add(name)
      }
    }
  }
  return locals
}

/**
 * 箭头函数参数名（`p => …`、`(a, b) => …`、`({ post: p }) => …`）：
 * 它们是表达式内部的局部变量，不是契约里的标识符。
 */
function arrowParamNames(expression) {
  const names = new Set()
  for (const match of expression.matchAll(/(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g)) {
    for (const part of (match[1] ?? match[2] ?? '').split(',')) {
      let name = part.split('=')[0].trim().replace(/^\.\.\./, '')
      if (name.includes(':')) {
        name = name.split(':').pop().trim()
      }
      name = name.replace(/[{}\[\]]/g, '').trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) {
        names.add(name)
      }
    }
  }
  return names
}

/** 去掉箭头函数参数声明，避免 `p =>` 里的 `p` 被当成自由标识符 */
function stripArrowParams(expression) {
  return expression.replace(
    /(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g,
    (match) => ' '.repeat(match.length),
  )
}

/** 提取表达式中的自由标识符（去掉字符串、属性访问、对象字面量键、箭头函数参数） */
function freeIdentifiers(expression) {
  const arrowParams = arrowParamNames(expression)
  const cleaned = stripArrowParams(
    expression.replace(/'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`/g, ' '),
  )
  const names = []
  const pattern = /(?<![\w$.])([A-Za-z_$][\w$]*)/g
  let match
  while ((match = pattern.exec(cleaned))) {
    const name = match[1]
    if (arrowParams.has(name)) {
      continue
    }
    const before = cleaned.slice(0, match.index).trimEnd().slice(-1)
    const after = cleaned.slice(pattern.lastIndex).match(/^\s*:/)
    if (after && (before === '{' || before === ',')) {
      continue
    }
    names.push(name)
  }
  return names
}

const SLOT_NAMES = new Set(
  Object.values(CONTRACT_COMPONENTS).flatMap((entry) => entry.slots.map((slot) => slot.name)),
)
const SLOT_SCOPE = new Map()
for (const entry of Object.values(CONTRACT_COMPONENTS)) {
  for (const slot of entry.slots) {
    if (!SLOT_SCOPE.has(slot.name)) {
      SLOT_SCOPE.set(slot.name, new Set())
    }
    slot.provides.forEach((field) => SLOT_SCOPE.get(slot.name).add(field))
  }
}

function scanTheme(file, html) {
  const locals = collectLocals(html)

  for (const match of html.matchAll(/<\s*(blog-[a-z-]+)/g)) {
    if (!CONTRACT_COMPONENT_NAMES.has(match[1])) {
      errors.push(`${rel(file)}:${lineAt(html, match.index)} 使用了未登记的组件 <${match[1]}>`)
    }
  }

  // slot 名与作用域字段（union 口径：不区分具体组件，后端校验同口径）
  for (const match of html.matchAll(/(?:#([\w-]+)|v-slot(?::([\w-]+))?)\s*=\s*"([^"]*)"/g)) {
    const name = match[1] ?? match[2] ?? 'default'
    if (!SLOT_NAMES.has(name)) {
      errors.push(`${rel(file)}:${lineAt(html, match.index)} 使用了未登记的 slot #${name}`)
      continue
    }
    const value = match[3].trim()
    const inner = value.startsWith('{') ? value.slice(1, value.lastIndexOf('}')) : value
    for (const part of inner.split(',')) {
      const field = part.split(':')[0].trim().replace(/^\.\.\./, '')
      if (field && /^[A-Za-z_$][\w$]*$/.test(field) && !SLOT_SCOPE.get(name)?.has(field)) {
        errors.push(
          `${rel(file)}:${lineAt(html, match.index)} slot #${name} 引用了未登记的作用域字段 "${field}"`,
        )
      }
    }
  }

  // 必填 props 与组件事件
  for (const match of html.matchAll(/<\s*(blog-[a-z-]+)([^>]*)>/g)) {
    const tag = match[1]
    const attrs = match[2]
    const entry = CONTRACT_COMPONENTS[tag]
    if (!entry) {
      continue
    }
    for (const prop of entry.requiredProps) {
      const pattern = new RegExp(`(?<![\\w-])(?:(?::|v-bind:)${prop}(?=[\\s=/>])|${prop}\\s*=)`)
      if (!pattern.test(attrs)) {
        errors.push(`${rel(file)}:${lineAt(html, match.index)} <${tag}> 缺少必填 prop "${prop}"`)
      }
    }
    if (entry.events.length) {
      for (const event of attrs.matchAll(/(?:^|\s)(?:@|v-on:)([\w-]+)/g)) {
        if (!entry.events.includes(event[1])) {
          errors.push(`${rel(file)}:${lineAt(html, match.index)} <${tag}> 使用了未登记事件 "${event[1]}"`)
        }
      }
    }
  }

  for (const match of html.matchAll(/\s(v-[a-z-]+)/g)) {
    if (!CONTRACT_DIRECTIVES.has(match[1])) {
      errors.push(`${rel(file)}:${lineAt(html, match.index)} 使用了未登记的指令 ${match[1]}`)
    }
  }

  const expressions = []
  for (const match of html.matchAll(/{{([\s\S]*?)}}/g)) {
    expressions.push({ text: match[1], index: match.index })
  }
  for (const match of html.matchAll(/\s([:@][A-Za-z0-9_.:[\]-]+|v-(?:bind|on|if|else-if|for|show|model|text|once)[A-Za-z0-9_.:[\]-]*)="([^"]*)"/g)) {
    expressions.push({ text: match[2], index: match.index })
  }

  for (const { text, index } of expressions) {
    for (const name of freeIdentifiers(text)) {
      if (JS_KEYWORDS.has(name) || locals.has(name) || TEMPLATE_KEYS.has(name)) {
        continue
      }
      errors.push(
        `${rel(file)}:${lineAt(html, index)} 引用了未登记的标识符 "${name}"（契约见 apps/blog/src/contract.ts）`,
      )
    }
  }
}

const starter = await readText(starterHtml)
scanTheme(starterHtml, starter)

const starterCssFile = path.join(root, 'packages/theme-starter/theme.css')
const starterCss = await readText(starterCssFile)
const unknownStarterClasses = [...cssClasses(starterCss)].filter((name) => !publicParts.has(name))
if (unknownStarterClasses.length) {
  errors.push(`默认主题样式化了未登记的组件 class → ${unknownStarterClasses.join(', ')}`)
}

// 能力 fixture：锁住合法写法不被误判（新增表达力时在这里补一段）
const capabilitiesFixture = path.join(root, 'scripts/fixtures/theme-capabilities.html')
scanTheme(capabilitiesFixture, await readText(capabilitiesFixture))

// headless fixture：锁住 root slot 的使用方式（2A）
const headlessFixture = path.join(root, 'scripts/fixtures/theme-headless.html')
scanTheme(headlessFixture, await readText(headlessFixture))

const extraThemeIndex = process.argv.indexOf('--theme')
if (extraThemeIndex >= 0) {
  const extra = process.argv[extraThemeIndex + 1]
  if (!extra) {
    errors.push('--theme 需要一个文件路径')
  } else {
    const file = path.resolve(extra)
    scanTheme(file, await readText(file))
  }
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------

const emitted = { ...contract.CONTRACT, types: sortedTypes }
const serialized = `${JSON.stringify(emitted, null, 2)}\n`
const backendOutput = path.join(repoRoot, 'backend', 'src', 'main', 'resources', 'theme', 'theme-contract.json')

if (process.argv.includes('--emit')) {
  const output = path.join(root, 'apps/blog/dist/theme-contract.json')
  await mkdir(path.dirname(output), { recursive: true })
  await writeFile(output, serialized, 'utf8')
  await mkdir(path.dirname(backendOutput), { recursive: true })
  await writeFile(backendOutput, serialized, 'utf8')
  console.log(`已生成 ${rel(output)} 与 ${rel(backendOutput)}`)
} else {
  const existing = await readFile(backendOutput, 'utf8').catch(() => null)
  const normalizeEol = (text) => text.replace(/\r\n/g, '\n')
  if (existing === null) {
    errors.push(`缺少后端契约生成物 ${rel(backendOutput)}，请运行 pnpm sync:contract`)
  } else if (normalizeEol(existing) !== normalizeEol(serialized)) {
    errors.push(`后端契约生成物过期 ${rel(backendOutput)}，请运行 pnpm sync:contract`)
  }
}

if (errors.length) {
  console.error(`主题契约自检失败（${errors.length} 项）：`)
  for (const message of errors) {
    console.error(`  - ${message}`)
  }
  process.exit(1)
}

console.log(
  `主题契约自检通过：${TEMPLATE_KEYS.size} 个模板键 / ${CONTRACT_COMPONENT_NAMES.size} 个组件 / ` +
    `${CONTRACT_CSS_VARS.size} 个变量 / ${CONTRACT_DIRECTIVES.size} 个指令`,
)
