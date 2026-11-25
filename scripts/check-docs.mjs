#!/usr/bin/env node
/**
 * 文档结构自检：只校验“可判定”的事实，不判断内容对错。
 *
 * 1. 引用可达：`docs/x.md` 必须存在；`docs/x.md §N` 的章节号必须在目标文件里；
 *    反引号里的仓库路径必须存在（允许文档按 `frontend/` 相对书写）。
 * 2. 表格完整：同一张表的每行列数一致（未转义的 `|` 会断列）。
 * 3. 索引完整：`docs/*.md` 必须在根 AGENTS.md 的索引表里（缺项 = error），
 *    索引行只写状态、不写 `vX.Y` 版本号（版本留在各文档 header）。
 * 4. 编号唯一：同一文件里 `## N` / `### N.M` 不重复（复制粘贴最常见的错）。
 * 5. 权限码对账：`docs/content-model.md` 的内容域码集合 == `Perms.java` 的对应常量。
 *
 * 用法：node scripts/check-docs.mjs [--quiet]
 * 退出码：有 error 时为 1；warn 不影响退出码。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const quiet = process.argv.includes('--quiet')
const SKIP_DIRS = new Set([
  'node_modules', '.git', '.idea', '.opencode', '.run', '.local',
  'dist', 'build', 'bin', 'logs', 'media', 'target', '.ruff_cache',
])

const files = []
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (!SKIP_DIRS.has(e.name)) walk(path.join(dir, e.name))
    } else if (e.name.endsWith('.md')) {
      files.push(path.join(dir, e.name))
    }
  }
}
walk(root)

const rel = (p) => path.relative(root, p).replaceAll('\\', '/')
const docs = new Map()
for (const f of files) docs.set(rel(f), fs.readFileSync(f, 'utf8').split(/\r?\n/))

const errors = []
const warns = []
const at = (file, line, msg, level = 'error') => {
  const entry = `${file}:${line}: ${msg}`
  ;(level === 'error' ? errors : warns).push(entry)
}
const exists = (p) => fs.existsSync(path.join(root, p))

// ---------- 1. 标题编号 ----------
const headingNumbers = new Map()
for (const [file, lines] of docs) {
  const nums = new Set()
  let fenced = false
  lines.forEach((line, i) => {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced
    if (fenced) return
    const m = /^#{2,6}\s+(\d+(?:\.\d+)*)(?=[\s.、]|$)/.exec(line)
    if (!m) return
    if (nums.has(m[1])) at(file, i + 1, `重复的章节号 §${m[1]}`, 'warn')
    nums.add(m[1])
  })
  headingNumbers.set(file, nums)
}

// ---------- 2. 引用与表格 ----------
const docRef = /docs\/([\w.-]+?\.md)`?\s*(?:的\s*)?§\s*(\d+(?:\.\d+)*)/g
const anyDoc = /docs\/([\w.-]+?\.md)/g
const codePath = /`((?:desktop|backend|frontend|scripts|docs|deploy|docker)\/[\w./-]+)`/g

for (const [file, lines] of docs) {
  let fenced = false
  let tableStart = -1
  let tableCols = -1

  lines.forEach((line, i) => {
    if (/^\s*(```|~~~)/.test(line)) {
      fenced = !fenced
      return
    }
    if (fenced) return

    for (const m of line.matchAll(docRef)) {
      const target = `docs/${m[1]}`
      if (!docs.has(target)) {
        at(file, i + 1, `引用了不存在的文件 ${target}`)
        continue
      }
      const nums = headingNumbers.get(target)
      if (nums && nums.size > 0 && !nums.has(m[2])) {
        at(file, i + 1, `${target} 没有 §${m[2]}`)
      }
    }

    for (const m of line.matchAll(anyDoc)) {
      if (!docs.has(`docs/${m[1]}`)) at(file, i + 1, `引用了不存在的文件 docs/${m[1]}`)
    }

    for (const m of line.matchAll(codePath)) {
      const p = m[1]
      if (p.includes('*') || p.includes('<') || p.endsWith('/')) continue
      if (exists(p) || exists(`frontend/${p}`)) continue
      at(file, i + 1, `路径不存在：${p}`, 'warn')
    }

    // 表格列数（跳过围栏代码块）
    if (/^\s*\|/.test(line)) {
      if (tableStart < 0) {
        tableStart = i + 1
        tableCols = undefined
      }
      const cells = line.replaceAll('\\|', '').split('|').length - 1
      if (tableCols === undefined) tableCols = cells
      else if (cells !== tableCols) {
        at(file, i + 1, `表格列数 ${cells} 与表头（第 ${tableStart} 行，${tableCols} 列）不一致`)
      }
    } else if (tableStart > 0) {
      tableStart = -1
      tableCols = -1
    }
  })
}

// ---------- 3. 索引完整 ----------
const agents = docs.get('AGENTS.md')
if (!agents) {
  at('AGENTS.md', 1, '找不到根 AGENTS.md')
} else {
  const listed = new Set()
  agents.forEach((line, i) => {
    if (!/^\s*\|/.test(line)) return
    for (const m of line.matchAll(/`docs\/([\w.-]+?\.md)`/g)) {
      if (!listed.has(m[1])) listed.add(m[1])
    }
    if (/^\s*\|\s*`docs\//.test(line) && !/`docs\/[\w.-]+?\.md`/.test(line)) {
      at('AGENTS.md', i + 1, '索引行疑似缺少完整的 `docs/xxx.md` 路径')
    }
    if (/^\s*\|\s*`docs\/[\w.-]+?\.md`/.test(line) && /v\d+\.\d+/.test(line)) {
      at('AGENTS.md', i + 1, '索引行不要写版本号（版本只写在各文档 header）')
    }
  })
  for (const file of docs.keys()) {
    if (!file.startsWith('docs/')) continue
    const name = file.slice('docs/'.length)
    if (!listed.has(name)) at('AGENTS.md', 1, `文档索引缺少 ${file}`)
  }
}

// ---------- 4. 内容域权限码对账 ----------
const PERMS = 'backend/src/main/java/cc/naihao/simplenotes/security/Perms.java'
const CONTENT_DOC = 'docs/content-model.md'
if (!exists(PERMS)) {
  warns.push(`跳过权限码对账：找不到 ${PERMS}`)
} else if (!docs.has(CONTENT_DOC)) {
  warns.push(`跳过权限码对账：找不到 ${CONTENT_DOC}`)
} else {
  const codeRe = /"(admin:(?:note|post|tag|media)(?::[a-z-]+)*)"/g
  const docRe = /`(admin:(?:note|post|tag|media)(?::[a-z-]+)*)`/g
  const inCode = new Set(
    [...fs.readFileSync(path.join(root, PERMS), 'utf8').matchAll(codeRe)].map((m) => m[1]),
  )
  const inDoc = new Set([...docs.get(CONTENT_DOC).join('\n').matchAll(docRe)].map((m) => m[1]))
  for (const c of inCode) if (!inDoc.has(c)) at(CONTENT_DOC, 1, `缺少权限码 ${c}（Perms.java 有、文档没有）`)
  for (const c of inDoc) if (!inCode.has(c)) at(CONTENT_DOC, 1, `多出权限码 ${c}（文档有、Perms.java 没有）`)
}

// ---------- 输出 ----------
for (const w of warns) if (!quiet) console.warn(`warn  ${w}`)
for (const e of errors) console.error(`error ${e}`)
const suffix = `${docs.size} 个 Markdown 文件，${errors.length} error，${warns.length} warn`
console.log(errors.length ? `文档自检失败：${suffix}` : `文档自检通过：${suffix}`)
process.exit(errors.length ? 1 : 0)
