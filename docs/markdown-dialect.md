# Markdown 内容方言规范

> **版本：v1（草案）· 2026-10-17（2026-10-18 修订）**
> 适用：博客文章与随手记（统一方言，不做区分）
> 验收样本：`docs/markdown-dialect-fixture.md`

本规范定义 Simple Notes 中「允许书写的 Markdown 语法」与「禁区」，用于约束三端行为一致：

| 端 | 引擎 | 角色 |
| --- | --- | --- |
| 管理端（Web） | md-editor-v3 7.x（`markdown-it` 内核） | 书写 + 预览 |
| 博客前台（Web） | 待定（复用 MdPreview 或自建 `markdown-it + shiki`） | 发布渲染 |
| 桌面端 | LiveMarkdown.Avalonia 2.4.3（`Markdig` 内核） | 书写 + 预览 |
| 后端 | 不解析、不渲染 | 原样存储 + 安全清洗 |

两个内核（markdown-it / Markdig）扩展集与细节语义不同，本规范是唯一事实来源；**新增语法必须三端验收通过才能进入白名单**（见 §7）。

---

## 1. 基线：CommonMark + GFM

以下语法无条件支持，三端必须一致：

- 标题 h1–h6、段落、分隔线
- 强调：`**粗体**` `*斜体*` `***粗斜体***`、删除线 `~~text~~`
- 列表：无序 / 有序 / 嵌套（含混排）
- 引用：可嵌套
- 链接：内联链接、自动链接（裸 URL）
- 图片：`![alt](url)`（引用规则见 §5）
- 行内代码与围栏代码块
- GFM 增量：**表格**（含对齐与 `\|` 转义）、**任务列表**（含嵌套）、删除线、自动链接

**换行语义**：遵循 CommonMark —— 段内单次回车为软换行（渲染为空格）。需要视觉换行时用空行分段；**不依赖行尾双空格**产生硬换行（可能被存储清洗剥离）。

## 2. 白名单（扩展）

### 2.1 代码块（核心场景，最高优先级）

- 围栏必须使用三反引号 + 语言标识：```` ```java ````
- info string 仅承诺**语言标识**；附加信息（如文件名、`title=`）v1 不承诺渲染
- 语言集见 **附录 A**；未收录语言不禁止使用，但高亮效果不承诺
- 围栏内内容原样呈现（含缩进、反引号）

### 2.2 Mermaid 图表

- 语法：```` ```mermaid ```` 围栏
- **承诺三端一致的图种**：`flowchart`（流程图）、`sequenceDiagram`（时序图）、`classDiagram`（类图）、`erDiagram`（ER 图）
- 其余图种（`gantt`、`mindmap`、`timeline`、`pie` 等）：web 端可正常渲染，**桌面端不承诺**（可能降级为代码块显示）

### 2.3 提示块（GitHub Alerts）

语法（必须位于引用块首行，大写）：

```
> [!NOTE] / [!TIP] / [!IMPORTANT] / [!WARNING] / [!CAUTION]
```

- 五种全部支持；样式由各端主题决定（颜色/图标可有差异，结构必须一致）
- 状态：Markdig 原生支持；md-editor-v3 内置的是 `!!!` 容器语法（`docarys/admonition`，与方言不符），需通过 `markdownItConfig` 追加 `markdown-it-github-alerts` 插件对齐（实现阶段验证）

## 3. 禁区（不收）

| 禁止内容 | 理由 | 预期行为 |
| --- | --- | --- |
| 裸 HTML（内联 / 块级 / iframe / video） | XSS 安全 + 三端差异最大 | 入库清洗；渲染端转义或移除 |
| YAML front matter | 元数据走数据库字段（§4） | 按普通文本处理 |
| 数学公式 `$...$` / `$$...$$` | v2 候选，当前场景（代码博客）低频 | 显示为纯文本 |
| 脚注 `[^1]` | v2 候选 | 显示为纯文本 |
| `==高亮==`、上下标 `H~2~O`、Emoji 短代码 `:smile:` | 保持方言最小 | 显示为纯文本 |
| 图片尺寸语法 `![alt\|300](url)`（Obsidian 风格） | 尺寸由渲染端样式控制 | 显示为纯文本 |
| Obsidian / Typora 等其他方言特有语法 | 无法三端保证 | 显示为纯文本 |

**编辑器端要求**：应关闭未收扩展的渲染（若默认开启，如 KaTeX），隐藏未收语法的工具栏入口，保证"编辑器预览所见 = 发布后所得"。

## 4. 元数据策略

- **正文零元数据**：不写 front matter，不写自定义头部
- 标题、标签、发布日期、发布状态、封面等全部为数据库字段，由后端领域模型 / API 契约定义，管理端与桌面端通过表单编辑

## 5. 资源引用规范

- 图片 / 附件经**后端上传接口**获取站内 URL，正文中引用站内路径（具体 URL 形态由 API 设计定）
- 外链图片：允许但不受保障（失效 / 防盗链 / 隐私）；桌面预览按 HTTP 缓存策略处理，加载失败显示 alt 文本
- alt 文本：建议填写（可访问性）
- 图片尺寸不写进语法（见 §3）

## 6. 各端配置对齐清单

| 端 | 配置要求 |
| --- | --- |
| 管理端（md-editor-v3） | 追加 `markdown-it-github-alerts` 插件；禁用内置 `!!!` admonition；`noKatex` / `noEcharts` 关闭未收语法；工具栏隐藏 sub/sup 等未收入口；`breaks: false` 对齐 CommonMark |
| 博客前台 | **复用 `MdPreview`**（与编辑器同源，配置经全局 `config()` 共享）：hljs 自托管（生产不依赖 CDN）；Mermaid 全图种；`html: false` 默认符合方言 |
| 桌面端（LiveMarkdown） | 管线启用 GFM + Alerts；不启用数学 / 脚注扩展；注册 Mermaid 模块并限定承诺图种 |
| 后端 | 入库清洗（HTML 处理策略在 API 设计中定）；v1 不做服务端渲染 |

## 7. 验收机制与变更流程

1. 语法提案 → 在 `docs/markdown-dialect-fixture.md` 增加样本节
2. 三端渲染 fixture → 记录差异（截图 / 备注）
3. 通过 → 白名单更新 + 版本号 +1；不通过 → 不收录（或记录为"单端增强，不入方言"）
4. fixture 中标注 **✗ 预期为纯文本** 的节用于验证禁区降级行为，同样需要三端一致

## 8. 未决事项

- [ ] md-editor-v3 追加 `markdown-it-github-alerts` 的兼容性验证（v7 管线与插件共存）；`noKatex` / `noEcharts` 关闭配置确认；`breaks: false` 生效确认
- [ ] LiveMarkdown.Avalonia 用于静态内容渲染的用法确认（`DocumentUpdate` / builder 模式）
- [x] ~~博客前台渲染管线选型~~ → **已定（2026-10-18）：复用 `MdPreview`**；实现阶段待办：读者端产物体积实测 + hljs 自托管配置
- [ ] 后端入库清洗策略细节（依赖 API 设计）

## 附录 A：代码块语言集（三端高亮承诺）

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

说明：别名（如 `js`、`sh`、`cs`）渲染结果不承诺，写作时使用上表主标识。

## 附录 B：版本记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1（草案） | 2026-10-17 | 初稿：代码博客 + 记事本最小集；Alerts 列入白名单（待验收）；数学/脚注等列为禁区（v2 候选） |
| v1（草案） | 2026-10-18 | 修订：博客前台渲染管线定为复用 `MdPreview`（与编辑器同源）；Alerts 对齐方式明确（追加 github-alerts 插件、禁用 `!!!` 语法）；§6 三端配置清单更新 |
