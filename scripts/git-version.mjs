// scripts/git-version.mjs —— 版本唯一来源：git（docs/release.md）。
//
// 构建脚本（build-backend / build-desktop）与管理端 vite.config 统一从这里取值，
// 人永远不需要手写版本号。显式环境变量仅给无 git 的 CI / tarball 构建用。
import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const root = join(dirname(fileURLToPath(import.meta.url)), '..')

function git(args) {
  try {
    return execFileSync('git', args, {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
  } catch {
    return ''
  }
}

export function getVersionInfo() {
  // describe 形态：v0.2.0（tag 上）/ 0.2.0-3-gabc1234（tag 之后）/ abc1234（尚无 tag）；
  // 脏树自动带 -dirty 后缀。无 git 时回退 dev 标记。
  const describe =
    process.env.RELEASE_VERSION || git(['describe', '--tags', '--dirty', '--always']) || ''
  const version = describe.replace(/^v/, '') || '0.0.0-dev'
  const commit = process.env.RELEASE_COMMIT || git(['rev-parse', '--short', 'HEAD']) || 'unknown'
  const buildTime =
    process.env.RELEASE_BUILD_TIME || git(['log', '-1', '--format=%cI']) || 'unknown'
  return { version, commit, buildTime }
}

// 直接执行时打印 JSON（调试 / 其他脚本组合用）。
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(getVersionInfo()))
}
