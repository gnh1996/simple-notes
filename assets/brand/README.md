# 品牌图标

Simple Notes 的图标源文件与各端导出。图形语义：**纸页 = 随手记，书签 = 博客 / 阅读**。

| 文件 | 用途 |
| --- | --- |
| `simple-notes.svg` | 设计源文件，64×64 viewBox（可无损缩放） |
| `simple-notes-512.png` | 512×512 透明 PNG（Avalonia 窗口图标 / 文档与商店素材） |
| `simple-notes.ico` | Windows 可执行文件图标，含 16 / 24 / 32 / 48 / 64 / 128 / 256 多尺寸 |

## 引用位置

- Web favicon：`frontend/apps/admin/public/favicon.svg`、`frontend/apps/blog/public/favicon.svg`（源文件副本）
- 桌面端：`desktop/src/SimpleNotes.Desktop/Assets/icon.png`（非 Windows 窗口图标）、`icon.ico`（Windows 窗口 + 可执行文件图标）

## 约定

- 颜色取自 `frontend/packages/ui/src/tokens.css` 的 brand 色板，不在各端另调：
  - 底板渐变 `#4f86f7 → #2d5bd0`（brand-500 与 brand-600 之间）
  - 书签 `#4377f0`（brand-500），次级文字线 `#6997fb`（brand-400）
- 修改图标时先改 `simple-notes.svg`，再同步 512 PNG 与多尺寸 ICO，最后更新上述副本
- 单色 / 托盘图标等新变体放本目录，命名 `simple-notes-<variant>.<ext>`
