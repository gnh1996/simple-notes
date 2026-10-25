import { copyFileSync, readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 内置默认主题同步（docs/blog-runtime-theme.md §4.2）。
 *
 * 唯一源：frontend/packages/theme-starter/{theme.html,theme.css}
 * 副本：backend/src/main/resources/blog/default-theme/（后端拼壳兜底需要自包含）
 *
 * 用法：
 *   pnpm sync:theme    写入副本
 *   pnpm check:theme   CI / 提交前校验副本未漂移
 */
const here = dirname(fileURLToPath(import.meta.url))
const sourceDir = join(here, '..', 'packages', 'theme-starter')
const targetDir = join(here, '..', '..', 'backend', 'src', 'main', 'resources', 'blog', 'default-theme')
const files = ['theme.html', 'theme.css']
const check = process.argv.includes('--check')

function digest(content) {
  return createHash('sha256').update(content).digest('hex')
}

for (const file of files) {
  const source = join(sourceDir, file)
  const target = join(targetDir, file)
  if (check) {
    let targetContent
    try {
      targetContent = readFileSync(target)
    } catch {
      console.error(`[theme] 缺少后端副本：${target}`)
      process.exitCode = 1
      continue
    }
    if (digest(readFileSync(source)) !== digest(targetContent)) {
      console.error(`[theme] 副本已漂移：${file}（执行 pnpm sync:theme）`)
      process.exitCode = 1
    }
  } else {
    copyFileSync(source, target)
    console.log(`[theme] 已同步：${file}`)
  }
}

if (check && !process.exitCode) {
  console.log('[theme] 内置默认主题副本一致')
}
