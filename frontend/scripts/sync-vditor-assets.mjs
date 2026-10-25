/**
 * 把 Vditor 运行时资源裁剪后同步到应用的 public 目录。
 *
 * 只保留本项目方言 v2 需要的子集：Lute / hljs / KaTeX / Mermaid / 图标 / i18n / 内容主题。
 * 不引入 CDN，博客与桌面端可离线；MathJax、Graphviz、ECharts、PlantUML 等一律不打包。
 */
import { cpSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'

const cwd = process.cwd()
const require = createRequire(join(cwd, 'package.json'))
const vditorDist = join(dirname(require.resolve('vditor/package.json')), 'dist')
const target = join(cwd, 'public', 'vditor')

rmSync(target, { recursive: true, force: true })

function copy(relative) {
  const from = join(vditorDist, relative)
  const to = join(target, 'dist', relative)
  mkdirSync(dirname(to), { recursive: true })
  cpSync(from, to, { recursive: true })
}

for (const relative of [
  'method.min.js',
  'js/lute/lute.min.js',
  'js/highlight.js/highlight.min.js',
  'js/highlight.js/styles/github.min.css',
  'js/highlight.js/styles/github-dark.min.css',
  'js/mermaid/mermaid.min.js',
  'js/katex',
  'js/icons/ant.js',
  'js/i18n/zh_CN.js',
  'css/content-theme/light.css',
  'css/content-theme/dark.css',
]) {
  copy(relative)
}

// Vditor 会在 highlight.min.js 之后加载该文件；用它注册附录 A 需要但 hljs 不自带的别名。
writeFileSync(
  join(target, 'dist', 'js', 'highlight.js', 'third-languages.js'),
  `(function () {
  if (!window.hljs || !window.hljs.registerAliases) return;
  if (!window.hljs.getLanguage('vue')) {
    window.hljs.registerAliases('vue', { languageName: 'xml' });
  }
})();
`,
)

let bytes = 0
function walk(dir) {
  const out = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else out.push(full)
  }
  return out
}
for (const file of walk(target)) {
  bytes += statSync(file).size
}

console.log(`[vditor] ${cwd} -> public/vditor (${(bytes / 1024 / 1024).toFixed(1)} MB)`)
