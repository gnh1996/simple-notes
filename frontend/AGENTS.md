# frontend 工作区规则

pnpm workspace：`apps/admin`（管理端）+ `apps/blog`（博客 runtime）+ `packages/*`（共享）。结构、命令与选型见 `README.md` 与 `docs/tech-stack.md`。

## 依赖与包管理

- **版本统一**：所有依赖版本写在根 `pnpm-workspace.yaml` 的 `catalog`，子包一律用 `"xxx": "catalog:"` 引用；禁止在子包写死版本
- **包边界**：`apps/*` 之间互不依赖；共享代码下沉 `packages/*`，用 `"workspace:*"` 引用（内部包直接导出 TS 源码，无构建步骤）
- 新增依赖前：确认与 Vue 3.5 / Vite 8 兼容、许可证可自由使用、维护活跃

## Markdown（与内容方言强相关，详见 `docs/markdown-dialect.md`）

- 编辑器：md-editor-v3 7（仅 admin）；**博客前台不再复用 `MdPreview`**，改由 blog runtime 内置 `markdown-it` 渲染（见 `docs/blog-runtime-theme.md`）
- 两端配置必须语义一致（admin 走全局 `config()`；blog 走 runtime 内的等价实现，由 fixture 双向验证）：
  - `breaks: false`（对齐 CommonMark 软换行）
  - 追加 `markdown-it-github-alerts`；禁用内置 `!!!` admonition
  - 过滤 katex / sub / sup / echarts 等未收扩展（`noKatex` / `noEcharts`）
  - hljs 自托管（生产不依赖 CDN）
- 代码块语言集与方言白名单一致；新增 Markdown 语法前必须先在 `docs/markdown-dialect-fixture.md` 加样本并完成三端验收

## 响应式

- `useBreakpoint()`（`@simple-notes/shared`）是唯一的断点判断来源，禁止 UA / 设备类型判断
- 路由与业务逻辑只有一份；只在展示层按断点分叉（如桌面端侧边栏 vs 移动端抽屉，见 `apps/admin/src/layouts/AdminLayout.vue`）

## 博客前台（blog runtime）

> 设计与实现见 `docs/blog-runtime-theme.md`（v1.3 / 方案 C 已落地）。

- **构建产物**：`apps/blog` 是 Vite + TS 的 runtime（无 SFC 依赖，组件模板在运行时编译）；产物入口固定为 `runtime.js`，Maven `prepare-package` 会拷入后端 jar 的 `static/blog/`
- **首屏**：唯一启动路径 = 后端 `BlogPageController` 组装壳（站点配置 + 主题 HTML/CSS 内联）；runtime 只读 `window.__BLOG_BOOT__`，不再有客户端 bootstrap 启动分支
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile，`asset-base` 自动指向 Vite（`http://localhost:5174`）获得 HMR；生产 jar 不启用，固定 `/blog`
- 主题来自数据库且**禁止携带脚本**；runtime 加载前必须再执行一次 `validateTheme`（保存时后端已校验）
- 内置默认主题唯一源：`packages/theme-starter/`；后端兜底副本用 `pnpm sync:theme` 同步、`pnpm check:theme` 校验，禁止只改一边
- Markdown 方言从 `@simple-notes/shared` 取（`markdownDialect` / `hljs` / github-alerts），不得在 app 内另写一份配置
- 运行时只调用公开接口；依赖全部打进 bundle，**禁止引用公共 CDN**
- 断点值与 `BREAKPOINTS` 保持一致（runtime 通过 CSS 媒体查询，不按 UA 判断）

## 管理端（apps/admin）

- **服务端菜单驱动**：侧边栏渲染 `me.menus`；路由由页面注册表（`src/router/page-registry.ts`，权限码 → 路径 / 组件 / 图标）映射后 `addRoute`；注册表未命中按目录递归；前端无路由级权限判断（`docs/auth-rbac.md` §6.2）
- **权限**：按钮用 `v-perm="'admin:xxx:yyy'"`（无码移除 DOM；超管自动放行）；接口 401 → 清凭证回登录页，403 `PERMISSION_DENIED` → 刷新 `me` 并重建菜单 / 路由
- **API 调用**：只用 `src/api` 的实例（自动注入 Bearer 与统一错误处理），禁止在页面里裸调 fetch
- **样式**：Element Plus 经 `@layer element-plus` 导入（层序 `theme, base, components, element-plus, utilities`）；Tailwind 工具类可直接覆盖组件样式；应用级覆盖写在 `styles/main.css` 末尾
- **主题 / 布局**：暗色主题由 `useTheme`（模块级单例 + `html.dark`）驱动，首屏由 `index.html` 内联脚本防闪白；多标签页状态在 `stores/tabs`（内存态，不做持久化）

## 命令

```bash
pnpm install
pnpm dev:admin            # 5173
pnpm dev:blog             # 5174（runtime dev server；页面走后端 8080）
pnpm typecheck            # 全仓类型检查（含默认主题副本校验）
pnpm build:admin          # 单应用构建
pnpm build:blog           # 产出 runtime.js；Maven 打包时拷入后端 jar
pnpm sync:theme           # packages/theme-starter → 后端兜底副本
pnpm check:theme          # 校验默认主题副本未漂移
pnpm gen:api              # 依后端 OpenAPI 重新生成 packages/api 类型（需后端已启动）
```
