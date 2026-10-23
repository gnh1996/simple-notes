# Simple Notes · 桌面端（Avalonia）

桌面客户端：功能精简的跨平台应用，承载随手记与博客写作、发布到 Web 博客端。

> **当前阶段：框架骨架**。只包含技术栈接线与项目结构；领域模型、存储、同步、UI 信息架构等属于功能设计范畴，定稿后再实现。

## 已定架构决策

| 决策点 | 结论 | 备注 |
| --- | --- | --- |
| 技术栈 | .NET 10 + Avalonia 12.1 | 本机 SDK 10.0.400；选型期做过内存实测 |
| MVVM | CommunityToolkit.Mvvm 8.4 | 源生成器、无反射、AOT 友好 |
| DI | Microsoft.Extensions.DependencyInjection | 组合根在 `App.axaml.cs` |
| 数据模式 | 本地优先 · 离线可用 | 实现细节待功能设计 |
| 分发 | 预留 NativeAOT 单文件发布 | 代码保持 AOT 兼容（`IsAotCompatible`） |
| 内存基线 | ~74MB（无渲染栈实测） | 典型桌面运行预估 90–140MB |

功能库选型随功能设计引入，已确认的候选与结论：

| 用途 | 选型 | 状态 |
| --- | --- | --- |
| Markdown 编辑 | AvaloniaEdit 12.0.0（+ AvaloniaEdit.TextMate 语法高亮） | 兼容已验证，随编辑功能设计引入 |
| Markdown 渲染/预览 | **LiveMarkdown.Avalonia 2.4.3**（Apache-2.0） | **已定**。Markdig + TextMate 内核；表格 / 跨块选中 / 图片异步缓存 / LaTeX / Mermaid（纯 .NET 实现、AOT 友好）；其流式 API 用于静态预览的用法在设计阶段适配 |
| ~~Avalonia.Controls.Markdown~~ | 官方控件，但属 Avalonia Pro 商业档（€899/年/席位） | 弃用（个人项目不走商业授权） |

## 结构

```
desktop/
├── SimpleNotes.Desktop.slnx
└── src/SimpleNotes.Desktop/
    ├── Program.cs           入口
    ├── App.axaml(.cs)       应用样式 + DI 组合根
    ├── Assets/              品牌图标（窗口 PNG / Windows ICO）
    ├── ViewModels/          ViewModelBase、MainWindowViewModel（占位）
    └── Views/               MainWindow（占位壳）
```

## 常用命令

在 `desktop/` 目录下执行：

| 命令 | 说明 |
| --- | --- |
| `dotnet build -c Release` | 构建 |
| `dotnet run --project src/SimpleNotes.Desktop` | 启动（需要图形环境） |
| `dotnet publish -c Release -r linux-x64 -p:PublishAot=true -p:PublishSingleFile=true` | NativeAOT 单文件发布（需 clang；平台参数如 win-x64 / osx-arm64） |

## 环境说明

本仓库开发服务器无图形环境（无 X / Wayland / fontconfig），GUI 需在有桌面的机器上运行验证（中文输入法等体验项也应实机验证）。

## 下一步（功能设计阶段）

- [ ] 领域模型与本地存储 schema（笔记、草稿、发布状态、标签）
- [ ] 同步协议（离线补发、冲突策略、本地/远端 ID 映射）
- [ ] 后端 API 契约（与 Web 端共用 OpenAPI 文档）
- [ ] 桌面端信息架构与编辑体验设计（编辑基于 AvaloniaEdit、预览基于 LiveMarkdown.Avalonia）
- [ ] 认证方案（token 存储：libsecret / DPAPI / Keychain）
