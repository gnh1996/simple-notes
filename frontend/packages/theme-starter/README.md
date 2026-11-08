# 内置默认主题（Ink & Paper）

这是博客 runtime 的**兜底主题**，也是管理端「新建主题」的起点。它同时承担两种角色：

- 站点未配置主题 / 主题契约不兼容时，保证博客仍然可用；
- 新主题的参考实现，示范契约允许的写法与边界。

因此它不能只对某一份站点配置“看起来还行”，必须在**默认配置**、**空数据**和**长文本**下都成立。

## 唯一源与副本

```text
frontend/packages/theme-starter/{theme.html,theme.css}   ← 唯一源
backend/src/main/resources/blog/default-theme/            ← pnpm sync:theme 同步的兜底副本
```

只改源文件；改完必须执行：

```bash
cd frontend
pnpm sync:theme
pnpm check:theme
pnpm check:contract
```

`check:theme` 校验两份文件哈希一致，`check:contract` 校验模板只使用契约登记的组件 / 指令 / 标识符 / CSS class。

## 硬约束

- **零脚本**：只能使用 `theme.html` + `theme.css`；不能出现 `<script>`、`v-html`、`window` / `document` / `fetch` / `localStorage`。
- **组件白名单**：只用 `apps/blog/src/contract.ts` 中登记过的组件、props、事件、slot 与指令。
- **class 白名单**：样式化组件时只使用 `CONTRACT_COMPONENTS[].parts` 与 `CONTRACT_GLOBAL_PARTS` 中登记的 `.bc-*`；自定义 class 可以自由取名。
- **断点**：与 `@simple-notes/shared` 的 `BREAKPOINTS` 保持一致（`768` / `1024`），不要另起 640 / 720 / 900 / 1080。
- **触控**：可点元素在 `pointer: coarse` 下至少 `44 × 44px`。
- **正文**：中文正文 16–17px，行高 ≥ 1.7，阅读区 ≤ 72ch。
- **对比度**：正文与次要信息 ≥ 4.5:1；暗色代码块必须和页面背景有可见边界。
- **安全区**：保留 `env(safe-area-inset-*)`，320 / 375 / 414 无横向滚动。
- **可空数据**：`author.name`、`footer.text`、`social.links`、`icp`、`policeRecord` 默认都可能是空值；空列要隐藏，空标签不要留下。

## 视觉约定

- 纸墨色板：暖纸底 + 朱砂点缀 + 衬线正文。
- 默认不启用首字下沉：首段只有一行时会侵入下一块，主题作者可按需自行加。
- 页脚字号要低于内容标题；默认配置下页脚只剩标识也算合法状态。
- 英文小标签（Index / Journal / Topics / Archive / Contents）是主题的语言风格，不是站点配置。

## 验收矩阵

每次改完至少手动过一遍：

| 维度 | 用例 |
| --- | --- |
| 站点配置 | 默认站点（无社交 / 无 ICP / 无页脚文案 / 无作者名） |
| 站点配置 | 有 3 条社交链接、ICP、公安备案、长页脚文案 |
| 站点名 | 默认长度；超长中文名 |
| 列表 | 0 篇、1 篇、多页 |
| 文章 | 有 h2/h3（目录正常）；无 h2/h3（目录栏应隐藏） |
| 上下篇 | 两篇都有；只有上一篇；只有下一篇 |
| 视口 | 320 / 375 / 414 / 768 / 1440 |
| 明暗 | light / dark（含代码块、callout、页脚） |
| 页面 | home / post / tags / tag-posts / archives / search / about / 404 |

预览方式：用 `fixtures=1` 启动预览壳即可拿到示例文章、标签和归档数据；长站名与空配置需要单独改 `__BLOG_BOOT__.site` 验证。

## 已知取舍

- 页脚不再用固定三列，改为 `auto-fit`，列数随实际填写的字段变化。
- `--blog-code-bg` 只用于行内代码；代码块使用主题内部的 `--code-block-bg`，因为行内浅底和块级深底无法共用一个变量。
- `--blog-gap` 是间距基准，参与主区底部留白、文章栏间距和页脚行距；站点覆盖会实际生效。
