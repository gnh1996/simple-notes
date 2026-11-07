#!/usr/bin/env node
/**
 * 发布版本脚本（docs/release.md §3）。
 *
 *   node scripts/release-version.mjs v0.2.0           只检查：tag 合法性、工作区干净、列出将变更的文件
 *   node scripts/release-version.mjs v0.2.0 --apply   检查 + 原地更新平台元数据（版本号去掉 v）
 *
 * 只改"版本号"这一行，不动其他内容；Windows manifest 用 X.Y.Z.0 四段格式。
 * No dependencies.
 */
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

const tag = process.argv[2]
const apply = process.argv.includes('--apply')

function fail(message) {
  console.error(`release-version: ${message}`)
  process.exit(1)
}

function sh(cmd, args) {
  return execFileSync(cmd, args, { cwd: root, encoding: 'utf8' }).trim()
}

if (!tag) fail('用法：node scripts/release-version.mjs vX.Y.Z [--apply]')
const m = /^v(\d+)\.(\d+)\.(\d+)(-rc\.\d+)?$/.exec(tag)
if (!m) fail(`tag 非法：${tag}（期望 vX.Y.Z 或 vX.Y.Z-rc.N）`)
const version = `${m[1]}.${m[2]}.${m[3]}${m[4] ?? ''}`
const isPrerelease = Boolean(m[4])
// Windows 四段版本：manifest / syso 要求数字段，预发布后缀只放展示字段
const win4 = `${m[1]}.${m[2]}.${m[3]}.0`

// 工作区必须干净（未跟踪文件允许：构建产物 / 本地配置不在发布内容里）
let status = ''
try {
  status = sh('git', ['status', '--short'])
} catch {
  fail('git 不可用')
}
const dirty = status
  .split('\n')
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('??'))
if (dirty.length > 0) {
  fail(`工作区不干净，拒绝发版：\n${dirty.join('\n')}`)
}

// tag 不可复用
let existing = ''
try {
  existing = sh('git', ['tag', '--list', tag])
} catch { /* 老仓库无 tag 时忽略 */ }
if (existing === tag) fail(`tag 已存在，不可移动 / 覆盖：${tag}`)

const edits = [
  {
    file: 'desktop/build/config.yml',
    // 只匹配 info 块下缩进的 version（顶格的 version: '3' 是 Task 版本，不能动）
    apply: (s) => s.replace(/^  version:\s*'[^']*'\s*$/m, `  version: '${version}'`),
    expect: new RegExp(`^  version:\\s*'${version.replace(/\./g, '\\.')}'\\s*$`, 'm'),
  },
  {
    file: 'desktop/build/windows/info.json',
    apply: (s) =>
      s
        .replace(/"file_version":\s*"[^"]*"/, `"file_version": "${version}"`)
        .replace(/"ProductVersion":\s*"[^"]*"/, `"ProductVersion": "${version}"`),
    expect: new RegExp(`"ProductVersion":\\s*"${version.replace(/\./g, '\\.')}"`),
  },
  {
    file: 'desktop/build/windows/wails.exe.manifest',
    apply: (s) => s.replace(/version="[^"]*"/, `version="${win4}"`),
    expect: new RegExp(`version="${win4.replace(/\./g, '\\.')}"`),
  },
  {
    file: 'desktop/build/windows/nsis/wails_tools.nsh',
    apply: (s) =>
      s.replace(/!define INFO_PRODUCTVERSION "[^"]*"/, `!define INFO_PRODUCTVERSION "${version}"`),
    expect: new RegExp(`!define INFO_PRODUCTVERSION "${version.replace(/\./g, '\\.')}"`),
  },
  {
    file: 'desktop/build/darwin/Info.plist',
    apply: (s) =>
      s
        .replace(
          /(<key>CFBundleShortVersionString<\/key>\s*<string>)[^<]*(<\/string>)/,
          `$1${version}$2`,
        )
        .replace(
          /(<key>CFBundleVersion<\/key>\s*<string>)[^<]*(<\/string>)/,
          `$1${version}$2`,
        ),
    expect: new RegExp(
      `<key>CFBundleShortVersionString<\\/key>\\s*<string>${version.replace(/\./g, '\\.')}</string>`,
    ),
  },
  {
    file: 'desktop/build/linux/nfpm/nfpm.yaml',
    apply: (s) => s.replace(/^version:\s*".*"\s*$/m, `version: "${version}"`),
    expect: new RegExp(`^version:\\s*"${version.replace(/\./g, '\\.')}"\\s*$`, 'm'),
  },
  {
    file: 'frontend/apps/desktop/package.json',
    apply: (s) => s.replace(/"version":\s*"[^"]*"/, `"version": "${version}"`),
    expect: new RegExp(`"version":\\s*"${version.replace(/\./g, '\\.')}"`),
  },
]

let failed = 0
for (const e of edits) {
  const path = join(root, e.file)
  let src = ''
  try {
    src = readFileSync(path, 'utf8')
  } catch {
    console.error(`  缺失文件：${e.file}`);
    failed += 1
    continue
  }
  const next = e.apply(src)
  if (next === src) {
    console.error(`  未匹配到版本号行：${e.file}`)
    failed += 1
    continue
  }
  if (!e.expect.test(next)) {
    console.error(`  替换后校验失败：${e.file}`)
    failed += 1
    continue
  }
  if (apply) {
    writeFileSync(path, next)
    console.log(`  已更新：${e.file}`)
  } else {
    console.log(`  将更新：${e.file}`)
  }
}
if (failed > 0) fail(`${failed} 个文件处理失败`)

console.log(`\n版本：${version}${isPrerelease ? '（预发布）' : ''} ← ${tag}`)
try {
  const sha = sh('git', ['rev-parse', '--short', 'HEAD'])
  console.log(`commit：${sha}`)
} catch { /* 忽略 */ }
if (!apply) {
  console.log('\n检查通过。确认后执行：')
  console.log(`  node scripts/release-version.mjs ${tag} --apply`)
} else {
  console.log('\n元数据已更新。后续：提交 → 打 tag → 按 docs/release.md §3 构建。')
}
