# 桌面端错误处理与运行日志设计（v1）

> **状态：v1 已实现（2026-11-04，Windows）**
> 范围：桌面端（Go + Wails + `frontend/apps/desktop`）运行期错误分类、用户可见提示、诊断详情、落盘日志与日志入口
> 关联：`docs/desktop-layout.md` §7.2（状态栏）、`docs/desktop-sync.md`（离线 / outbox）、`docs/desktop-local-schema.md`（迁移失败）
> 非目标：崩溃上报服务、远程日志、应用内日志查看器、前端持久化日志、性能指标采集

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 用户可见错误 | 只展示**可行动的短句**；不展示绑定层原文、URL、`dial tcp` / `connectex` 等平台细节 |
| 网络失败 | **不进入红色错误区**；只更新“离线”状态与同步入口，符合 `desktop-layout.md` §5.1 / §7.2 |
| 错误详情 | 状态栏“详情”打开弹层，显示完整错误链；可复制详情 / 诊断信息、打开日志目录 |
| 日志落盘 | Go 侧统一写结构化 JSON 行日志，前端经 binding 写入**同一个文件** |
| 日志位置 | 用户缓存目录：`<UserCacheDir>/simple-notes/logs/desktop.log`（Windows 为 `%LocalAppData%\simple-notes\logs\desktop.log`） |
| 轮转 | 单文件 5MB，保留 4 个备份（`desktop.log.1` … `.4`） |
| 级别 | `debug` / `info` / `warn` / `error`；默认 `info`，可用 `SIMPLE_NOTES_LOG_LEVEL` 调整 |
| 脱敏 | 令牌、密码、请求体、笔记正文、标题等字段统一打码；查询串不落日志 |
| 诊断入口 | 账户菜单：打开日志目录 / 复制诊断信息；错误详情弹层同样提供 |
| 启动失败 | 仍写 `desktop-error.log`（兼容旧约定），同时写入统一日志 |

## 1. 背景

截图里的状态栏直接显示了 Wails binding 返回的原始错误：

```
RuntimeError: 请求后端失败: Get "http://localhost:8080/api/desktop/notes/sync?limit=500":
dial tcp 127.0.0.1:8080: connectex: No connection could be made because the target machine actively...
```

问题有三类：

1. **对用户无意义**：这是“后端没启动 / 不可达”，用户需要知道的是“当前离线，本地内容仍可用”，而不是 TCP 错误；
2. **违反既有布局设计**：`desktop-layout.md` §5.1 明确“同步失败不弹错误横幅，只在顶栏 / 状态栏标记离线”，§7.2 明确“网络失败不进入红色错误区”；
3. **排查能力缺失**：错误只存在于 UI，关掉提示就没了；运行期没有日志，只有启动失败写 `desktop-error.log`。

## 2. 三层分离

| 层 | 位置 | 内容 | 保留策略 |
| --- | --- | --- | --- |
| 用户提示 | 状态栏 / 对话框 | 短句 + 可行动动作（重试 / 登录 / 详情） | 成功提示 4s；错误保留到下一次操作 |
| 诊断详情 | 错误详情弹层 | 完整错误消息、`cause`、堆栈；复制 / 打开日志 | 当前错误期间 |
| 运行日志 | `desktop.log` | 时间、级别、事件名、操作字段、错误链 | 5MB × 4；不自动上传 |

原则：**用户提示不承担排查职责，日志不承担用户提示职责。**

## 3. 错误分类

前端 `src/lib/errors.ts` 的 `describeError()` 把异常归一为六类：

| kind | 判定 | 用户提示 | 状态栏位置 |
| --- | --- | --- | --- |
| `offline` | `请求后端失败` / `dial tcp` / `connectex` / `connection refused` / `i/o timeout` / `context deadline exceeded` 等 | 「无法连接服务器，本地内容仍可用」 | **不显示红色**；右侧变“离线”，顶栏同步入口显示圆点 |
| `auth` | `会话已失效` / `HTTP 401` | 「登录已失效，请重新登录」 | 红色错误 + 详情 |
| `outdated` | `云端版本较低` / 服务端过旧 | 沿用 Go 侧文案 | 红色错误 + 详情 |
| `server` | `HTTP 5xx` / `Internal Server Error` | 有服务端 message 用 message，否则「服务器暂时不可用，请稍后重试」 | 红色错误 + 详情 |
| `local` | 文件、SQLite、业务校验等 | 原始业务文案（通常是中文短句） | 红色错误；校验类尽量留在对话框内联 |
| `unknown` | 其它 | 调用方 fallback | 红色错误 + 详情 |

同步失败走 `reportSyncError()`：`offline` 只改状态；其它类别进入状态栏错误区，并保留“同步失败”作为弱提示。

## 4. 状态栏与详情弹层

### 4.1 状态栏（26px）

- 左：活动标签保存状态、字数、待同步、成功提示（绿，4s）、错误（红，短句 + “详情”）。
- 右：来源细节、在线 / 离线、账号。
- 网络失败不写 `error`，只置 `online = false`。
- `syncError` 只作为非网络同步失败的弱提示，不再承载原始异常。

### 4.2 错误详情弹层

- 入口：状态栏错误后的“详情”。
- 展示：错误类别徽标、用户短句、完整技术详情（`Error.name`、`message`、`cause`、堆栈）。
- 动作：
  - **复制详情**：短句 + 技术详情；
  - **复制诊断信息**：应用版本、OS、日志路径、最近 100 条日志；
  - **打开日志目录**：系统文件管理器打开日志目录。

### 4.3 账户菜单

始终提供：

- 打开日志目录；
- 复制诊断信息（与弹层同一份内容）。

不要求登录，便于本地模式下排查。

## 5. 日志设计

### 5.1 位置与文件

- 目录：`os.UserCacheDir()/simple-notes/logs`，失败回退 `os.UserConfigDir()/simple-notes/logs`；
- 文件：`desktop.log`；
- 权限：目录 `0700`，文件 `0600`；
- 轮转：写入后超过 5MB 关闭句柄，按 `desktop.log.1` … `desktop.log.4` 轮转（Windows 下先关闭再重命名）。

### 5.2 格式

每行一个 JSON 对象：

```json
{"time":"2026-11-04T10:20:30.123+08:00","level":"warn","event":"api.request","message":"请求后端失败","fields":{"method":"GET","path":"/api/desktop/notes/sync","duration_ms":"12","err":"dial tcp 127.0.0.1:8080: connectex: ..."}}
```

| 字段 | 说明 |
| --- | --- |
| `time` | RFC3339Nano，本地时区 |
| `level` | `debug` / `info` / `warn` / `error` |
| `event` | 稳定事件名，便于按操作过滤（见 §5.4） |
| `message` | 人类可读短句（≤4096 rune） |
| `fields` | 结构化字段；最多 20 个，单值 ≤512 rune |

### 5.3 脱敏

以下字段值统一写 `***`：

`token`、`access_token`、`refresh_token`、`password`、`passwd`、`pwd`、`secret`、`authorization`、`cookie`、`set-cookie`、`body`、`content`、`markdown`、`text`、`note`、`title`。

额外规则：

- API 日志只记录 `method`、去查询串的 `path`、`status`、`duration_ms`；
- 不记录请求 / 响应体；
- 搜索关键词、笔记标题、正文、附件内容均不落日志；
- 错误消息本身可能包含服务端业务文案，允许记录；但禁止把凭证拼进错误消息。

### 5.4 事件名

| 事件 | 来源 | 说明 |
| --- | --- | --- |
| `app.start` / `app.stop` / `app.run` | Go | 启动、退出、运行异常 |
| `db.migrate` | Go | 本地库迁移失败（阻塞启动） |
| `api.request` | Go | 网络失败（warn）、服务端 5xx（error）、401/403/409（warn）、其余（debug） |
| `auth.login` / `auth.current` / `auth.logout` | 前端 | 登录与会话 |
| `notes.*` | 前端 | 打开、新建、保存、删除、导入、导出、冲突处理 |
| `folders.*` | 前端 | 文件夹增删改查 |
| `files.*` | 前端 | 打开、保存、另存、最近文件 |
| `sync.refresh` / `sync.flush` | 前端 | 索引同步 / outbox 重试 |
| `ui.vue` / `ui.window` / `ui.promise` | 前端 | Vue 组件、脚本、未处理 Promise 兜底 |
| `logs.open` / `logs.diagnostics` | 前端 | 诊断入口自身失败 |

### 5.5 级别与噪声控制

- 默认 `info`：网络失败、4xx 冲突、业务结果错误可见；
- `debug`：成功请求等高频事件，默认不落盘；需要时用 `SIMPLE_NOTES_LOG_LEVEL=debug`；
- 同一网络故障不按请求次数刷屏：前端每次同步失败记录一条 `sync.refresh`（不逐条记录列表加载），Go 侧 `api.request` 按请求记录，便于确认重试节奏；后续如噪声过大再加去重窗口。

## 6. 前端接入

1. `src/main.ts` 安装全局兜底：
   - `app.config.errorHandler` → `ui.vue`；
   - `window.error` → `ui.window`；
   - `unhandledrejection` → `ui.promise`。
2. 所有业务 `catch` 使用 `setError(err, event, fallback)`，不再 `String(err)` 直接进 UI。
3. 标签内错误使用 `tabErrorText()`：网络类显示“离线，联网后重试”，其它显示短句。
4. 日志调用 fire-and-forget；binding 不可用（浏览器调试）时静默降级，不影响业务。

## 7. 与启动日志的关系

- `desktop-error.log` 保留：迁移失败 / 库版本过高时，GUI 进程可能还没有完整日志环境，先写该文件再弹原生对话框；
- 同一错误同时写入 `desktop.log`（`db.migrate`），便于统一收集；
- 文档与支持话术仍可让用户提供 `desktop-error.log` 或整个日志目录。

## 8. 隐私与安全

- 日志是本地诊断数据，默认不上传；
- 不记录 JWT、密码、Cookie、请求体、笔记正文与标题；
- “复制诊断信息”只包含版本、OS、路径、最近日志，不包含数据库内容；
- 打开日志目录是本地文件管理器操作，不经过网络。

## 9. 验收

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 后端未启动，已登录触发同步 | 状态栏不出现红色 TCP 错误；右侧显示“离线”；日志有 `api.request` warn |
| 2 | 后端恢复后点同步 | 显示“索引已同步…”；离线标记消失 |
| 3 | 服务端 500 | 状态栏短提示 + “详情”；详情含 HTTP 状态与错误链 |
| 4 | 令牌失效（401） | 提示“登录已失效，请重新登录” |
| 5 | 本地文件读取失败 | 标签内显示短错误，不泄露绑定层前缀 |
| 6 | 打开日志目录 / 复制诊断信息 | 账户菜单可用；诊断信息含最近日志、无令牌 |
| 7 | 日志轮转 | 超过 5MB 生成 `.1`，最多保留 `.4` |
| 8 | 迁移失败 | 弹原生对话框；`desktop-error.log` 与 `desktop.log` 均有记录 |

## 10. 已知限制与后续

- 日志内没有请求 ID / 会话 ID，跨前后端关联依赖时间与路径；
- 网络故障逐请求记录，长时间离线可能产生较多 `api.request` 行；
- 未做日志查看器，依赖系统文件管理器；
- 崩溃（进程级 panic / WebView 崩溃）尚未接入 Wails 崩溃钩子，后续可补；
- 后续如需要，可增加“导出诊断包”（zip：日志 + 版本 + schema），当前以复制文本为主。

## 11. 变更记录

| 日期 | 版本 | 说明 |
| --- | --- | --- |
| 2026-11-04 | v1 | 初版并实现：错误三层分离、网络失败降级、详情弹层、结构化日志 + 轮转 + 脱敏、账户菜单诊断入口、全局前端兜底 |
