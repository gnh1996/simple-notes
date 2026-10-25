import { cpSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 把 Vite 构建产物同步到 Go module 内的 embed 目录。
// Go 的 //go:embed 不能引用 module 之外的路径，因此不能直接嵌 ../frontend。
const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
const target = resolve(here, '../../../../desktop/web/dist')

mkdirSync(target, { recursive: true })
for (const entry of readdirSync(target)) {
  if (entry !== '.gitkeep') {
    rmSync(join(target, entry), { recursive: true, force: true })
  }
}
cpSync(dist, target, { recursive: true })
console.log(`[wails] synced ${dist} -> ${target}`)
