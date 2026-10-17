# desktop 端规则

.NET 10 + Avalonia 12 桌面端（随手记 + 博客写作 + 发布）。**当前为框架骨架**：功能设计定稿后才进入实现。结构与选型见 `README.md` 与 `docs/tech-stack.md`。

## 已定技术项

- 架构：CommunityToolkit.Mvvm + Microsoft.Extensions.DependencyInjection（组合根在 `App.axaml.cs`）
- 数据模式：本地优先（SQLite，离线可用 + 后台同步；同步协议待设计）
- Markdown：渲染 LiveMarkdown.Avalonia（静态内容用法待确认）；编辑 AvaloniaEdit（+ TextMate 高亮）
- 分发：预留 NativeAOT 单文件发布

## 硬约束（来自选型，改动需走变更决策）

- **内存优先**：不内嵌 WebView 做 Markdown 预览；新增依赖先评估内存与包体积
- **AOT / 裁剪友好**：保持 `IsAotCompatible`；JSON 一律用源生成（`JsonSerializerContext`）；避免反射与动态代码生成；新增依赖须评估 AOT 兼容性
- **许可证**：不使用需要商业许可的组件（如 Avalonia Pro 控件）
- **平台目标**：Windows / Linux / macOS

## 环境注意

- 本仓库的开发服务器**无图形环境**（无 X / Wayland / fontconfig）：构建与无头验证可行，GUI 与 UI 验证需在有桌面的机器上进行
- 中文输入法（fcitx5）与 Wayland 下的体验需要实机验证（选型期已知风险点）

## 命令

```bash
dotnet build -c Release
dotnet run --project src/SimpleNotes.Desktop   # 需要图形环境
# AOT 单文件发布示例见 README（需 clang）
```
