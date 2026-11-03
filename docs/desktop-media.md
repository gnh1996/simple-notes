# 桌面端图片：文件 / 本地 / 服务端（v1 已实现）

> **状态：v1 · 2026-11-02（Windows 构建、Go 单测、前端 typecheck 已通过；图形界面 / 三平台实机验收待做）**
> 范围：桌面临时文件、纯本地笔记、服务端笔记三条上下文的插图、预览、另存为、导出、纳入管理、同步到服务端
> 关联：`docs/attachment-storage.md`（后端媒体存储）、`docs/desktop-local-file.md`（临时文件 / 纳入管理 / 导出）、`docs/desktop-sync.md`（服务端笔记同步）、`docs/desktop-local-schema.md`（本地迁移）
> 非目标：桌面端附件库 UI、服务端附件删除、离线服务端图片队列、非图片文件插入、图片压缩 / 转码

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 文件模式 | 图片复制到 Markdown 同目录的 `<stem>.assets/`，正文写相对路径 |
| 本地笔记 | 图片存 `desktop.db` 同级的 `media/local/`，正文写 `local-media/<id>` |
| 服务端笔记 | 上传到 `POST /api/desktop/media`，正文写稳定地址 `/api/media/<id>` |
| 二进制传输 | 浏览器 `fetch` 到 Wails 同源 `/__media/import`，**不走 JSON binding / base64** |
| 预览 | 文件 / 本地用 Vditor `linkBase` + Go 媒体路由；服务端用同源 `/api/media` 代理 |
| 离线 | 本地 / 文件可离线插图；服务端笔记要求联网上传，v1 不排队 |
| 权限 | 新增 `desktop:media:upload`（`V17`），不复用 `admin:media:upload` |
| 删除 | 服务端附件留在媒体库；本地附件按引用清理；文件 assets 允许孤儿 |

## 1. 三种上下文

| 上下文 | 存储 | Markdown 引用 | 预览 | 同步 / 导出 |
| --- | --- | --- | --- | --- |
| 文件（临时编辑） | `<md 同目录>/<stem>.assets/` | `./<stem>.assets/<name>.<ext>` | `linkBase = <origin>/__media/file/<token>/` | 保存即随文件；另存为复制到新 assets |
| 本地笔记 | `media/local/<uuid>.<ext>` | `local-media/<uuid>` | `linkBase = <origin>/__media/` | 导出复制到目标 assets；同步到服务端时上传并改写 |
| 服务端笔记 | 后端附件（LOCAL / OSS / DATABASE） | `/api/media/<uuid>` | 同源 `/api/media/**` → Go 代理到后端 | 内容随服务端同步；博客 / admin 直接可用 |

原则：

- Markdown 只存可移植或稳定引用，不写 `file://`、不写会过期的 R2 签名地址。
- 文件模式保持外部编辑器可读；本地笔记是桌面端私有格式，导出 / 同步时再转换。
- 服务端图片不下载到本地缓存；离线时显示破图占位（v1 不缓存附件）。

## 2. 统一导入链路

```text
Vditor 工具栏 / 粘贴 / 拖拽（File）
  │  POST /__media/import?target=file|local|server&token=...&name=...
  │  Content-Type: 原始图片 MIME，body 为二进制
  ▼
Go media.Service（Wails 资产服务，Route=/__media）
  ├─ file   → 复制到 <stem>.assets/，返回 ref + URL
  ├─ local  → 写入 media/local/ + local_attachment，返回 local-media/<id>
  └─ server → 流式 POST /api/desktop/media，返回 /api/media/<id>
  ▼
Vditor upload handler → editor.insertValue("![alt](ref)")
```

- 只接受 PNG / JPEG / GIF / WebP / AVIF；用文件头判断，拒绝 SVG / HTML 伪装。
- 单张上限 20 MB，与后端附件上限一致。
- Vditor 的 `upload.handler` 返回字符串会被当作错误提示；桌面端 handler 在内部 `insertValue`，成功返回 `null`，失败返回错误文案。

## 3. 预览链路

```text
文件：img src = ./note.assets/a.png
  → Lute linkBase = <origin>/__media/file/<token>/
  → GET /__media/file/<token>/note.assets/a.png
  → Go 校验 token / 路径 / 符号链接 / 图片魔数后返回

本地：img src = local-media/<id>
  → Lute linkBase = <origin>/__media/
  → GET /__media/local-media/<id>
  → Go 查 local_attachment → media/local/<uuid>.<ext>

服务端：img src = /api/media/<id>
  → GET /api/media/<id>（Wails 同源）
  → Go 代理到后端 BaseURL；/oss/** 同样代理
```

`linkBase` 依赖 Lute 的 `trimLinkPath`：渲染时补全 URL，`getValue()` 时再裁掉 base，正文仍保留相对引用。`MarkdownEditor` 在切换标签 / token 到位时更新 `SetLinkBase` 并重新渲染。

## 4. 代码落点

### 4.1 桌面端 Go

| 文件 | 作用 |
| --- | --- |
| `desktop/internal/media/media.go` | `/__media` HTTP 路由、文件 token、本地存储、服务端上传、`/api/media` 与 `/oss` 代理 |
| `desktop/internal/media/refs.go` | Markdown 图片引用扫描 / 改写、另存为复制、导出复制、本地附件清理 |
| `desktop/internal/media/image.go` | 图片魔数检测与 20 MB 常量 |
| `desktop/internal/store/attachment.go` | `local_attachment` 读写 |
| `desktop/internal/store/migrations/0003_local_media.sql` | 本地附件表 |
| `desktop/internal/localfile` | `LocalFile.mediaToken`；`SaveAs` 复制 assets；`ExportMarkdown` 复制本地图片 |
| `desktop/internal/notes` | `ImportFile.sourcePath`；纳入本地 / 服务端、同步到服务端时改写图片引用；服务端保存时上传 `local-media` 引用 |
| `desktop/main.go` | 注册 `/__media` 服务；资产中间件代理 `/api/media/**`、`/oss/**` |

### 4.2 后端

| 文件 | 作用 |
| --- | --- |
| `web/DesktopMediaController.java` | `POST /api/desktop/media`，复用 `AttachmentService.upload` |
| `security/Perms.java` | `desktop:media:upload` |
| `V17__add_desktop_media_permission.sql` | 在 `entry:desktop` 下追加 ACTION |

### 4.3 前端

| 文件 | 作用 |
| --- | --- |
| `frontend/packages/markdown/src/index.ts` | `linkBase`、`uploadAccept` / `uploadMax` / `uploadMultiple` |
| `frontend/apps/desktop/src/components/MarkdownEditor.vue` | `/__media/import` 上传、`SetLinkBase`、插图后 `insertValue` |
| `frontend/apps/desktop/src/components/DocumentView.vue` | 按 tab 生成 `mediaContext` / `linkBase` |
| `frontend/apps/desktop/src/App.vue` | `mediaToken` 保存 / 释放、导入传 `sourcePath`、另存为传 `sourcePath` |

## 5. 数据与接口

### 5.1 本地表

```sql
create table local_attachment (
    id            text primary key,
    scope_id      text not null default 'local',
    original_name text not null,
    mime_type     text not null,
    size_bytes    integer not null,
    storage_key   text not null,
    create_time   integer not null
);
```

- 文件实际存在 `desktop.db` 同级的 `media/local/`。
- `storage_key` 为服务端生成的 UUID + 安全扩展名；`original_name` 只用于展示 / 导出。
- 本地附件不参与同步；本地笔记保存 / 删除后按 `note_local.content` 扫描清理未引用项。

### 5.2 桌面端上传接口

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/desktop/media` | `desktop:media:upload` | `multipart/form-data`，字段 `file`；返回 `AttachmentResponse` |

桌面会话不能访问 `/api/admin/**`，因此单独暴露最小上传接口；存储介质、大小、魔数、任意文件策略全部复用 `AttachmentService`。

### 5.3 `/__media` 内部路由

| 方法 / 路径 | 说明 |
| --- | --- |
| `POST /__media/import` | `target` 为 `file` / `local` / `server`；原始二进制 body；返回 `{ref,url,alt}` |
| `GET /__media/file/<token>/<rel>` | 文件模式图片；token 只由 `localfile` 登记 |
| `GET /__media/local-media/<id>` | 本地笔记图片 |
| `GET /api/media/**`、`GET /oss/**` | Wails 资产中间件代理到后端；只读 |

## 6. 流程

### 6.1 文件 → 纳入管理

- 仅本地：把相对图片复制到本地媒体库，正文改写为 `local-media/<id>`。
- 服务端：把相对图片上传到 `/api/desktop/media`，正文改写为 `/api/media/<id>`。
- 图片缺失或非图片：中止并提示；不生成半套引用。

### 6.2 本地笔记 → 同步到服务端

1. 扫描 `local-media/<id>`；
2. 逐张上传；
3. 全部成功后改写正文；
4. 移动 scope 并走现有 `PromoteLocal`；
5. 成功后清理不再被本地笔记引用的图片；失败保留本地笔记。

### 6.3 服务端笔记保存

- 正文出现 `local-media/<id>`（例如从本地笔记复制）时，保存前上传并改写为 `/api/media/<id>`。
- 离线或上传失败：本次保存报错，不把本地引用推到服务端。

### 6.4 另存为 / 导出

- 文件另存为到其他目录：把旧 `<stem>.assets/` 中被引用的图片复制到新目录并改写相对路径。
- 本地笔记导出：把 `local-media/<id>` 复制到导出文件旁的 `<导出名>.assets/`，改写相对路径。
- 服务端笔记导出：保留 `/api/media/<id>`，v1 不下载图片做自包含导出。

## 7. 安全与边界

| 项 | 规则 |
| --- | --- |
| 图片类型 | 只允许 PNG / JPEG / GIF / WebP / AVIF；SVG / HTML / 未知类型拒绝 |
| 大小 | 单张 20 MB；超限 413 / 400 |
| 路径 | `/__media/file/<token>/<rel>` 只允许 token 目录内、非符号链接逃逸、非目录 |
| 响应 | `X-Content-Type-Options: nosniff`；`Content-Disposition: inline`；文件模式 `no-store` |
| token | 128-bit 随机；文件标签关闭 / 另存为后释放 |
| 服务端 | 只走 `/api/desktop/**`；Bearer 由 Go 注入，不进入 JS |
| 大载荷 | 图片不走 JSON IPC / base64；Wails 资产服务流式接收，Go 再流式写盘 / 上传 |
| 离线 | 服务端笔记插图直接拒绝；不写临时本地路径 |
| 备份 | 本地图片在 `media/local/`，不在 `desktop.db.bak` 内；备份需同时保留媒体目录 |

## 8. 验收用例

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 文件模式插入截图 | 生成 `<stem>.assets/`，正文相对路径，预览可见 |
| 2 | 文件另存为到其他目录 | 图片复制并改写路径，外部编辑器可读 |
| 3 | 本地笔记离线插入 | 落 `media/local/`，正文 `local-media/<id>`，重启后可见 |
| 4 | 本地笔记导出 | 图片复制到导出 assets，导出文件可独立打开 |
| 5 | 本地笔记同步到服务端 | 图片上传，正文改 `/api/media/<id>`；失败时保留本地 |
| 6 | 服务端笔记插入 | 上传成功，正文 `/api/media/<id>`，博客前台可见 |
| 7 | 服务端离线插入 | 明确提示需联网，不写入本地临时路径 |
| 8 | 粘贴 / 拖拽图片 | 与工具栏上传一致，不经过 IPC / base64 |
| 9 | SVG / HTML / 超 20 MB | 拒绝并提示 |
| 10 | 路径穿越 / 符号链接逃逸 | `/__media/file` 返回 404 |
| 11 | 删除本地笔记 | 未被其他本地笔记引用的图片被清理 |
| 12 | 权限 | 无 `desktop:media:upload` 返回 403；启动对账通过 |

## 9. 已知限制 / 后续

- 服务端笔记离线插图、附件离线缓存：P1。
- 文件 assets 的孤儿图片清理：P1（当前不自动删除，避免误删其他文档引用的文件）。
- 服务端笔记导出为自包含 Markdown（下载图片）：P1。
- 本地附件库 / 图片管理 UI：非目标。
- `linkBase` 在 Windows 已构建通过；macOS / Linux 的 `wails://` origin 与 POST `/__media/import` 需实机验收。
