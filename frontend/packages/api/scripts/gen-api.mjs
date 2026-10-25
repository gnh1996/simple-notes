import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 生成 OpenAPI 类型（docs/tech-stack.md）：契约单一来源 = 运行中的应用。
 * 用 Node 读取 OPENAPI_URL，避免 Windows 上 shell 不展开 `${VAR:-default}`。
 */
const url = process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs'
const cwd = fileURLToPath(new URL('..', import.meta.url))
const require = createRequire(join(cwd, 'package.json'))
const packagePath = require.resolve('openapi-typescript/package.json')
const packageJson = JSON.parse(readFileSync(packagePath, 'utf8'))
const bin = typeof packageJson.bin === 'string'
  ? packageJson.bin
  : packageJson.bin['openapi-typescript']
const cli = join(dirname(packagePath), bin)

execFileSync(process.execPath, [cli, url, '-o', 'src/generated/schema.d.ts'], {
  cwd,
  stdio: 'inherit',
})
