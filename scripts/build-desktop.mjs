// scripts/build-desktop.mjs —— 桌面端构建 wrapper（docs/release.md §3）。
//
// 用法：node scripts/build-desktop.mjs [--package] [--dry-run]
// 平台自动判定（win32→windows），调用 wails3 task <os>:build|package 并注入版本三元组。
// 人不需要手写 VERSION= 等参数；Taskfile 侧的 | default 仅是无 wrapper 时的兜底。
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'
import { getVersionInfo, root } from './git-version.mjs'

const args = process.argv.slice(2)
const pkg = args.includes('--package')
const dryRun = args.includes('--dry-run')

const os = { win32: 'windows', darwin: 'darwin', linux: 'linux' }[process.platform]
if (!os) {
  console.error(`不支持的平台：${process.platform}（桌面端需在目标平台本机构建）`)
  process.exit(1)
}

const { version, commit, buildTime } = getVersionInfo()
// 安全阀：值须匹配白名单（与 build-backend.mjs 同规则）。
for (const [name, value] of Object.entries({ version, commit, buildTime })) {
  if (!/^[A-Za-z0-9:._+-]+$/.test(value)) {
    console.error(`拒绝构建：${name} 含非法字符：${value}`)
    process.exit(1)
  }
}
const taskArgs = [
  'task',
  `${os}:${pkg ? 'package' : 'build'}`,
  '--',
  `VERSION=${version}`,
  `COMMIT=${commit}`,
  `BUILD_TIME=${buildTime}`,
]
console.log(`desktop ${version} / ${commit} / ${buildTime} -> ${os}:${pkg ? 'package' : 'build'}`)
if (dryRun) {
  console.log(`wails3 ${taskArgs.join(' ')}`)
  process.exit(0)
}
const r = spawnSync('wails3', taskArgs, { cwd: join(root, 'desktop'), stdio: 'inherit', shell: false })
if (r.error) {
  console.error(`启动失败：${r.error.message}（需先安装 wails3 v3.1.0，见 desktop/README.md）`)
  process.exit(1)
}
process.exit(r.status ?? 1)
