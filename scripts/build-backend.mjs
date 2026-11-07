// scripts/build-backend.mjs —— 后端发布构建 wrapper（docs/release.md §3）。
//
// 用法：node scripts/build-backend.mjs [--skip-tests] [--dry-run]
// 从 git 推导 revision / commit / time，调用 mvnw package。
// Windows 下 .cmd 经 shell 启动；值先过字符白名单再拼命令，无引号坑、无注入。
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { getVersionInfo, root } from './git-version.mjs'

const args = process.argv.slice(2)
const dryRun = args.includes('--dry-run')
const skipTests = args.includes('--skip-tests')

const { version, commit, buildTime } = getVersionInfo()
const mvnw = join(root, 'backend', process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
if (!existsSync(mvnw)) {
  console.error(`找不到 ${mvnw}`)
  process.exit(1)
}
// 安全阀：值进 shell 前必须匹配白名单（tag 名理论上可含任意字符，不能拼进命令行）。
for (const [name, value] of Object.entries({ version, commit, buildTime })) {
  if (!/^[A-Za-z0-9:._+-]+$/.test(value)) {
    console.error(`拒绝构建：${name} 含非法字符：${value}`)
    process.exit(1)
  }
}

console.log(`backend ${version} / ${commit} / ${buildTime}`)
if (dryRun) {
  console.log(
    `${mvnw} -Drevision=${version} -Dproject.build.outputTimestamp=${buildTime}${skipTests ? ' -DskipTests' : ''} package`,
  )
  process.exit(0)
}
// Windows 下 .cmd 必须经 shell 启动；值已过白名单，拼单字符串（无参数数组 + shell 的 DEP0190 警告）。
const cmd =
  `"${mvnw}" -Drevision=${version} -Dproject.build.outputTimestamp=${buildTime}` +
  `${skipTests ? ' -DskipTests' : ''} package`
const r = spawnSync(cmd, { cwd: join(root, 'backend'), stdio: 'inherit', shell: true })
if (r.error) {
  console.error(`启动失败：${r.error.message}`)
  process.exit(1)
}
if (r.status !== 0) process.exit(r.status ?? 1)
console.log(`\n部署时设置运行时环境：APP_COMMIT=${commit} APP_BUILD_TIME=${buildTime}`)
