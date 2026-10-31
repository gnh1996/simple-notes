# Markdown 内容方言规范

> **版本：v2.1 · 2026-10-31**
> 适用：博客文章与随手记（统一方言，不做区分）
> 验收样本：`docs/markdown-dialect-fixture.md`
> 变更：v2 起允许数学公式、Emoji 短代码、脚注、上下标、mark 与**安全行内 HTML**；引擎统一为 Lute（Vditor）。v2.1 桌面端迁移到 Vditor 4.0.1 + Lute，三端同源。

本规范定义 Simple Notes 中「允许书写的 Markdown 语法」与「边界」，用于约束多端行为一致：

| 端 | 引擎 | 角色 | 状态 |
| --- | --- | --- | --- |
| 管理端（Web） | **Vditor 4.0.1（Lute）** | 书写 + 预览 | v2 已切换 |
| 博客前台（Web） | **Lute**（`@simple-notes/markdown` + `method.min.js`） | 发布渲染 | v2 已切换 |
| 桌面端（Wails） | **Vditor 4.0.1（Lute）** | 书写 + 预览 | v2.1 已切换（2026-10-31） |
| 后端 | 不解析、不渲染 | 原样存储 | 不做 HTML 清洗（见 §3） |

admin / blog / 桌面端共用同一份 Lute 配置（`frontend/packages/markdown/src/index.ts` 的 `vditorMarkdown`）；
**配置只允许有一份**，任何一端另写都会被视为方言漂移。

---

## 1. 基线：CommonMark + GFM

以下语法无条件支持，admin / blog / 桌面端必须一致：

- 标题 h1–h6、段落、分隔线
- 强调：`**粗体**` `*斜体*` `***粗斜体***`、删除线 `~~text~~`
- 列表：无序 / 有序 / 嵌套（含混排）
- 引用：可嵌套
- 链接：内联链接、自动链接（裸 URL）
- 图片：`![alt](url)`（引用规则见 §5）
- 行内代码与围栏代码块
- GFM 增量：**表格**（含对齐与 `\|` 转义）、**任务列表**（含嵌套，Lute 输出复选框）

**换行语义**：遵循 CommonMark —— 段内单次回车为软换行（渲染为空格）。需要视觉换行时用空行分段。

## 2. 白名单（扩展）

### 2.1 代码块（核心场景）

- 围栏使用三反引号 + 语言标识：```` ```java ````
- 语言集见 **附录 A**；未收录语言不禁止使用，但高亮效果不承诺
- `vue` 通过自托管 hljs 的别名（`vue → xml`）保证高亮；不要依赖 hljs 原生支持
- 代码高亮由 Vditor 的 hljs 运行时完成；博客加载后移除其主题样式，使用主题自己的 token 颜色

### 2.2 Mermaid 图表

- 语法：```` ```mermaid ````
- **承诺一致的图种**：`flowchart`、`sequenceDiagram`、`classDiagram`、`erDiagram`
- 其余图种（`gantt`、`mindmap`、`timeline`、`pie` 等）可渲染，但不承诺像素级一致
- Mermaid 资源按需加载；主题负责 `svg { max-width: 100% }` 等基础样式

### 2.3 提示块（GitHub Alerts）

```markdown
> [!NOTE] / [!TIP] / [!IMPORTANT] / [!WARNING] / [!CAUTION]
```

- 五种全部支持；Lute 输出结构为：

  ```html
  <div class="callout" data-subtype="NOTE">
    <div class="callout-info"><span class="callout-icon">…</span><span class="callout-title">Note</span></div>
    <div class="callout-content">…</div>
  </div>
  ```

- 主题按 `.callout[data-subtype="NOTE"]` 等选择器着色；标题文案由 Lute 决定（当前为英文）
- 不要再使用 `markdown-it-github-alerts` 的 `.markdown-alert-*` 结构

### 2.4 数学公式

- 行内 `$...$`、块级 `$$...$$`
- 渲染引擎：**KaTeX**（不启用 MathJax；MathJax 资源不打包）
- 公式块在清洗之后渲染，因此 DOMPurify 不会移除 KaTeX 输出

### 2.5 Emoji、脚注、上下标、mark

- Emoji 短代码（`:smile:`）由 Lute 转成 Unicode emoji
- 脚注 `[^1]`、上标 `^2^`、下标 `~2~`、`==高亮==` 均支持
- 这些语法在 v2 中属于白名单，不再做“降级为纯文本”的验收

### 2.6 安全行内 HTML

- 允许行内 HTML，但**渲染前必须清洗**：
  - 后端：只存原始 Markdown，不做解析 / 渲染
  - 前端：`packages/markdown` 的 `sanitizeHtml()`（DOMPurify）在 `innerHTML` 前执行
  - Vditor 编辑器：`preview.transform` + paste 事件清洗；保留 Lute 的 `sanitize: true`
- 清洗规则：移除 `script` / `iframe` / `object` / `embed` / `style` / 表单元素与事件属性；允许安全的格式、链接、图片、表格、callout、KaTeX/Mermaid 所需属性
- **可信作者模型**：编辑不对外开放，但仍必须保留清洗——粘贴内容同样不可信

## 3. 边界（不收 / 不承诺）

| 内容 | 处理 |
| --- | --- |
| YAML front matter | 按普通文本处理；元数据走数据库字段（§4） |
| 图片尺寸语法 `![alt\|300](url)` | 不承诺；尺寸由主题 CSS 控制 |
| Obsidian / Typora 专有语法（callout 折叠、块引用等） | 不承诺 |
| 不支持/未验证的 Mermaid 图种 | 可渲染但不承诺 |
| 服务端 Markdown 渲染 | 不做；后端只存原文 |

> v2 的“安全 HTML”是**清洗后的 HTML**，不是“任意 HTML”。后端不承担清洗职责，清洗发生在所有前端渲染入口。

## 4. 元数据策略

- **正文零元数据**：不写 front matter，不写自定义头部
- 标题、标签、发布日期、发布状态、封面等全部为数据库字段，由后端领域模型 / API 契约定义

## 5. 资源引用规范

- 图片 / 附件经**后端上传接口**获取站内 URL，正文中引用站内路径
- 外链图片允许但不受保障；加载失败显示 alt
- alt 文本建议填写；图片尺寸不写进语法

## 6. 各端配置对齐清单

| 端 | 配置要求 |
| --- | --- |
| 管理端（Vditor） | `createEditorOptions()`：`mode: 'ir'`、`vditorMarkdown`、`preview.transform = sanitizeHtml`、`hljs` 自托管、`cdn = /vditor`；工具栏可切换 IR / SV |
| 博客前台 | `createPreviewOptions()` + `renderToElement()`（`method.min.js`，不打包完整编辑器）；同一份 `vditorMarkdown`；`sanitizeHtml`；`cdn = /blog/vditor`；Math / Mermaid / hljs 按需加载 |
| 桌面端（Wails） | **待迁移**；迁移后与管理端完全同源。过渡期不参与方言验收 |
| 后端 | 原样存储；不做 Markdown 渲染、不做 HTML 清洗（可信作者 + 前端清洗） |

资源同步：`frontend/scripts/sync-vditor-assets.mjs` 裁剪并复制 Lute / hljs / KaTeX / Mermaid / icons / i18n / 内容主题到各应用 `public/vditor`。禁止引用公共 CDN。

## 7. 验收机制与变更流程

1. 语法提案 → 在 `docs/markdown-dialect-fixture.md` 增加样本节
2. **admin / blog / 桌面端渲染 fixture** → 记录差异（截图 / 备注）
3. 通过 → 白名单更新 + 版本号 +1；不通过 → 不收录
4. fixture 中的预期行为按 v2 解释：数学 / emoji / 安全 HTML 渲染属预期；front matter、图片尺寸语法仍为纯文本

## 8. 未决事项

- [x] 桌面端迁移到 Vditor / Lute（2026-10-31 已切换，Vditor 4.0.1 + Lute，与 admin / blog 同源）
- [ ] 桌面端三端 fixture 比对（迁移后补渲染记录）
- [ ] callout 标题为英文（Lute 内置），是否需要中文映射 / 自定义渲染器
- [ ] 博客主题 CSS 对 callout / 数学 / Mermaid / 任务列表的视觉验收
- [ ] 后端是否保留“写入时扫描危险 HTML”的额外防线（当前仅前端清洗）
- [ ] 超长文档（1 万字以上）在 Vditor IR 下的输入延迟实测

## 附录 A：代码块语言集（高亮承诺）

| 语言 | 围栏标识 |
| --- | --- |
| Java | `java` |
| SQL | `sql` |
| Shell | `bash` |
| JSON | `json` |
| YAML | `yaml` |
| XML | `xml` |
| HTML | `html` |
| CSS | `css` |
| JavaScript | `javascript` |
| TypeScript | `typescript` |
| Vue | `vue` |
| C# | `csharp` |
| Python | `python` |
| Dockerfile | `dockerfile` |
| Properties / INI | `properties` |
| Markdown | `markdown` |
| 纯文本（无高亮） | `text` |

说明：别名（如 `js`、`sh`、`cs`）渲染结果不承诺；`vue` 由自托管 hljs 别名保证。

## 附录 B：版本记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1（草案） | 2026-10-17 | 初稿：代码博客 + 记事本最小集；Alerts 列入白名单；数学/脚注等列为禁区 |
| v1（草案） | 2026-10-18 | 博客前台复用 `MdPreview`；Alerts 对齐方式明确 |
| v1（草案） | 2026-10-24 | 博客前台改为 runtime 内置 `markdown-it` |
| v1（草案） | 2026-10-25 | blog runtime 改为构建产物，共享 `markdownDialect` |
| v1（草案） | 2026-10-25 | 桌面端改为 Wails，复用 admin 编辑器 |
| **v2** | **2026-10-25** | **admin / blog 切换 Vditor + Lute；允许数学、emoji、脚注、上下标、mark、安全行内 HTML；新增 DOMPurify 清洗与 hljs `vue` 别名；桌面端待迁移** |
| **v2.1** | **2026-10-31** | **桌面端迁移到 Vditor 4.0.1 + Lute（资源自托管、粘贴清洗、主题切换），三端同源；待补三端 fixture 渲染记录** |
