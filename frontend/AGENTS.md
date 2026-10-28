# frontend 工作区规则

pnpm workspace：`apps/admin`（管理端）+ `apps/blog`（博客 runtime）+ `apps/desktop`（桌面端 UI）+ `packages/*`（共享）。结构、命令与选型见 `README.md` 与 `docs/tech-stack.md`。

## 依赖与包管理

- **版本统一**：所有依赖版本写在根 `pnpm-workspace.yaml` 的 `catalog`，子包一律用 `"xxx": "catalog:"` 引用；禁止在子包写死版本
- **包边界**：`apps/*` 之间互不依赖；共享代码下沉 `packages/*`，用 `"workspace:*"` 引用（内部包直接导出 TS 源码，无构建步骤）
- 新增依赖前：确认与 Vue 3.5 / Vite 8 兼容、许可证可自由使用、维护活跃

## Markdown（方言 v2；详见 `docs/markdown-dialect.md`）

- 编辑器：**Vditor 4.0.1**（admin；桌面端待迁移），IR 模式，可切 SV；配置来自 `@simple-notes/markdown`
- 博客前台：**Lute**（`@simple-notes/markdown` 的 `createPreviewOptions` + `renderToElement`，`method.min.js` 不打包完整编辑器）
- 单一语义源：`packages/markdown/src/index.ts` 的 `vditorMarkdown`；admin / blog 禁止另写配置
- 安全：`sanitizeHtml()`（DOMPurify）在所有 `innerHTML` 前执行；Vditor 预览 `transform` + paste 清洗；Lute `sanitize: true` 保留
- 资源：`pnpm sync:vditor` 裁剪复制到 `public/vditor`（Lute / hljs / KaTeX / Mermaid / icons / i18n / 内容主题）；禁止公共 CDN
- 代码高亮：自托管 hljs 的 `third-languages.js` 注册 `vue → xml` 别名；新增语言集需同步附录 A 与 fixture

## 响应式

- `useBreakpoint()`（`@simple-notes/shared`）是唯一的断点判断来源，禁止 UA / 设备类型判断
- 路由与业务逻辑只有一份；只在展示层按断点分叉（如桌面端侧边栏 vs 移动端抽屉，见 `apps/admin/src/layouts/AdminLayout.vue`）

## 博客前台（blog runtime）

> 设计与实现见 `docs/blog-runtime-theme.md`（v1.3 / 方案 C 已落地）。

- **构建产物**：`apps/blog` 是 Vite + TS 的 runtime；产物入口固定为 `runtime.js`，Maven `prepare-package` 会拷入后端 jar 的 `static/blog/`。**主题模板是运行时数据，必须保留 Vue 运行时编译器**（`vue` alias 指向 `vue/dist/vue.esm-bundler.js`，不要改成 runtime-only）；组件统一是 `.vue` 单文件组件（构建期编译，模板进 `vue-tsc` 类型检查）
- **首屏**：唯一启动路径 = 后端 `BlogPageController` 组装壳（站点配置 + 主题 HTML/CSS 内联）；runtime 只读 `window.__BLOG_BOOT__`，不再有客户端 bootstrap 启动分支。路由数据在 `beforeResolve` 取好、`afterEach` 一次性写入，壳内 `[data-blog-boot]` 加载层在 `data-blog-ready=1` 后淡出；主题可用 CSS 隐藏它
- **路由**：页面路由的唯一来源是 `apps/blog/src/runtime.ts` 的 vue-router 表；后端只做 history fallback，新增路由**不要**改后端
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile，`asset-base` 自动指向 Vite（`http://localhost:5174`）获得 HMR；生产 jar 不启用，固定 `/blog`
- 主题来自数据库且**禁止携带脚本**；runtime 加载前必须再执行一次 `validateTheme`（保存时后端已校验）
- 内置默认主题唯一源：`packages/theme-starter/`；后端兜底副本用 `pnpm sync:theme` 同步、`pnpm check:theme` 校验，禁止只改一边
- **契约单一来源**：主题可见的数据 / 动作 / 辅助函数 / 组件 / CSS 变量 / 允许指令只在 `apps/blog/src/contract.ts` 登记；`template-context.ts` 与 `components/*.vue`（注册表 `components/index.ts`，一个组件一个文件，组件内禁止 `<style>`）必须与之一致，`pnpm check:contract`（已并入 `typecheck`）会拦下"暴露了没登记 / 登记了没实现"；新增能力先改契约再改实现
- **组件样式接口**：组件模板里的 class（`.bc-*`，以及历史遗留的 `archive-index`）是公开样式接口（`CONTRACT_COMPONENTS[].parts`）；改组件内部结构时不得改名或删除已登记的 class，新增 class 要同步登记，否则自检失败
- **主题可写状态只有 `ui`**：抽屉 / 标签页 / 页内筛选等界面状态放 `ui`，路由切换即清空；不要把业务状态写进去。需要接管组件内部结构时用已登记的具名 slot（当前仅 `blog-post-card#default`），槽位与作用域变量同样在 `contract.ts` 登记
- Markdown 方言从 `@simple-notes/markdown` 取（`createPreviewOptions` / `renderToElement` / `sanitizeHtml`），不得在 app 内另写一份配置；Lute 与 hljs 资源由 `pnpm sync:vditor` 自托管
- 运行时只调用公开接口；依赖全部打进 bundle，**禁止引用公共 CDN**
- 断点值与 `BREAKPOINTS` 保持一致（runtime 通过 CSS 媒体查询，不按 UA 判断）

## 管理端（apps/admin）

- **服务端菜单驱动**：侧边栏渲染 `me.menus`；路由由页面注册表（`src/router/page-registry.ts`，权限码 → 路径 / 组件 / 图标）映射后 `addRoute`；注册表未命中按目录递归；前端无路由级权限判断（`docs/auth-rbac.md` §6.2）
- **权限**：按钮用 `v-perm="'admin:xxx:yyy'"`（无码移除 DOM；超管自动放行）；接口 401 → 清凭证回登录页，403 `PERMISSION_DENIED` → 刷新 `me` 并重建菜单 / 路由
- **API 调用**：只用 `src/api` 的实例（自动注入 Bearer 与统一错误处理），禁止在页面里裸调 fetch
- **样式**：Element Plus 经 `@layer element-plus` 导入（层序 `theme, base, components, element-plus, utilities`）；Tailwind 工具类可直接覆盖组件样式；应用级覆盖写在 `styles/main.css` 末尾
- **主题 / 布局**：暗色主题由 `useTheme`（模块级单例 + `html.dark`）驱动，首屏由 `index.html` 内联脚本防闪白；多标签页状态在 `stores/tabs`（内存态，不做持久化）

## 桌面端（apps/desktop）

> 壳与本地能力见 `desktop/AGENTS.md`；此处只约束前端部分。

- UI 由 `desktop/` 的 Wails WebView 加载；窗口、SQLite、HTTP、令牌都在 Go 侧
- **不直连后端**：只调用 Go binding `api.Do({ method, path, body })`；token 不进入 JS / localStorage
- `bindings/` 由 `wails3 task bindings` 生成（改 Go service 签名后重跑），随仓库提交；禁止手写 IPC 类型
- API 类型来自 `@simple-notes/api`（OpenAPI 生成），不另写接口类型；Go 侧只做通用转发
- Markdown 与 admin 同源：`configureMarkdown()` + `packages/shared`；新增语法仍按方言 fixture 流程
- 构建产物先 `pnpm build:desktop`，再由 `desktop/` 的 `wails3 build` 执行 `pnpm sync:wails` 同步进 `desktop/web/dist`
- 内存按 WebView 进程树评估：新增常驻面板 / 依赖前先实测（见 `desktop/README.md`）

## 命令

```bash
pnpm install
pnpm dev:admin            # 5173
pnpm dev:blog             # 5174（runtime dev server；页面走后端 8080）
pnpm dev:desktop          # 5175（桌面端 UI；binding 需在 Wails 壳内）
pnpm typecheck            # 全仓类型检查（含默认主题副本校验）
pnpm build:admin          # 单应用构建
pnpm build:blog           # 产出 runtime.js；Maven 打包时拷入后端 jar
pnpm build:desktop        # 桌面端 UI 构建；之后由 desktop/ 的 wails3 build 同步并嵌入
pnpm sync:theme           # packages/theme-starter → 后端兜底副本
pnpm check:theme          # 校验默认主题副本未漂移
pnpm gen:api              # 依后端 OpenAPI 重新生成 packages/api 类型（需后端已启动）
```
