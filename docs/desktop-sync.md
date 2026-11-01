# 桌面端同步设计（本地优先 + 懒加载）

> **状态：v1.4 · 2026-10-31（M0–M3 已实现：登录 / 索引同步 / 按需拉正文 / 编辑推送 / 删除墓碑 / 文件夹 / outbox / 离线新建 / 冲突 diff + 副本）**
> 范围：桌面端本地数据模型、同步协议、冲突处理、账号隔离、后端接口增量
> 关联：`docs/content-model.md`（内容模型 / 发布快照）、`docs/auth-rbac.md`（JWT 会话）、`docs/tech-stack.md`、`desktop/README.md`、`desktop/AGENTS.md`
> 非目标：多服务器 / 多账号同时在线、附件离线缓存、发布历史同步、实时协作、全文索引

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 同步范围 | 随手记（`NOTE`）+ 博客文章草稿（`POST`）；**不**同步 `note_revision` 历史；发布是显式动作 |
| 拉取策略 | **懒加载**：登录只拉索引（manifest），打开某篇才拉正文；未缓存的正文离线不可读 |
| 冲突判定 | 服务端 `version` 作修订号 + 本地内容 hash；二者分工，不混用 |
| 冲突解决 | **整篇二选一**（本地 / 服务端），弹窗带文本 diff；被覆盖的一侧自动留「冲突副本」 |
| 删除 | 软删除 + 墓碑同步；远端删除 / 本地删除都要传播 |
| 文件夹 | 同步（单层、量小）；**标签不同步**，只作为笔记字段（名字数组）随笔记走 |
| 离线新建 | 支持；笔记 id 由客户端生成 UUID，后端接受客户端 id |
| 账号 | 单服务器单账号；切换后只显示当前 scope 与纯本地笔记；旧 scope 数据保留但隐藏 |
| 附件 | v1 不缓存二进制；离线时显示占位，恢复网络后可看 |
| 传输 | 一律走 Go binding `api.Do`；令牌只在 SQLite；正文为 JSON 字符串 |

## 1. 目标与非目标

### 1.1 目标

1. 断网可编辑、可新建；恢复网络后自动推送，不丢内容。
2. 启动与登录快：只拉索引，不拉正文；不因笔记总量线性变慢。
3. 冲突可解释、可恢复：明确告诉用户哪一端改了什么，覆盖前留副本。
4. 多端（Web 管理端 / 桌面端）编辑同一篇时，服务端 `version` 保证不静默覆盖。
5. 数据按「服务器 + 账号」隔离，切号不串数据、不误推送。

### 1.2 非目标（v1）

- 多服务器 / 多账号同时在线、跨账号复制笔记；
- 附件二进制离线缓存、断点续传；
- 发布快照（`note_revision`）的历史浏览与回滚（后续复用 `GET /posts/{id}/revisions`）；
- 实时协同、自动三方合并（字段级合并后续再议）；
- 全文离线索引（v1 只搜标题 + 已缓存正文）。

## 2. 术语

| 术语 | 含义 |
| --- | --- |
| scope | 数据归属：`local`（纯本地）或 `srv:<serverId>:user:<userId>` |
| manifest | 服务端返回的轻量索引：id / kind / 标题 / 文件夹 / 置顶 / 版本 / 更新时间 / 是否删除 |
| base_version | 上一次成功同步时服务端返回的 `version` |
| remote_version | manifest 中当前服务端 `version` |
| dirty | 本地有未推送修改 |
| cached | 正文已拉到本地（`content_loaded = 1`） |
| hash | 同步字段的规范化 SHA-256，用于判断「内容是否真的不同」 |

## 3. 同步协议

### 3.1 为什么用 `version` 而不是服务端 hash

`NoteService.update()` 每次保存都会显式刷新 `update_time`，所以 `version` 每次 PUT 必涨——它**不是内容指纹**，但正好可以当「服务端修订号」：

- `version` 判断「远端是否变过」，服务端权威、列表已返回，零额外成本；
- 正文拉到本地后再算 `hash`，判断「变了之后是否真的不同」，抑制无意义冲突（例如 Web 端只改了置顶）。

因此 **v1 不需要服务端存 `content_hash` 列**。若将来要「不拉正文就判断内容相等」，再加列与回填（见 §12）。

### 3.2 manifest 拉取

```
GET /api/desktop/notes/sync?cursor=<opaque>&limit=500
GET /api/desktop/posts/sync?cursor=<opaque>&limit=500
→ { items: [{ id, kind, title, folderId, pinned, publishStatus,
              version, updateTime, deleted }], nextCursor, hasMore }
```

- **路径域必须是 `/api/desktop/**`**：`SessionJwtFilter` 校验会话端与路径域一致，`DESKTOP` 会话不能调用 `/api/admin/**`；
- 权限码：`desktop:note`（随手记 / 文件夹）、`desktop:post`（文章），见 §5 与 `V13__add_desktop_sync_permissions.sql`；
- 游标基于 `(coalesce(update_time, create_time), id)` keyset，避免深分页；`deleted=true` 的行来自软删除记录（专用 `JdbcTemplate` 读取，代码注释说明理由）；
- 首次同步 `cursor` 为空；之后从上次游标继续；
- `hasUnpublishedChanges` 不在 manifest 中（需要加载发布快照，成本高），由详情接口返回；
- 索引体量：1000 篇约 300KB（gzip ≈ 50KB），1–3 个请求。

登录**只**做认证与 `GET /auth/me`；manifest 在进入主界面后后台执行，不阻塞首屏。M0 实现中登录成功即触发一次索引同步。

### 3.3 判定表

对 manifest 每一项：

| 本地状态 | remote_version vs base_version | 动作 |
| --- | --- | --- |
| 不存在 | — | 插入存根（`content_loaded = 0`），**不拉正文** |
| 干净 | 相等 | 无变化 |
| 干净 | 不等 | 标记「远端有更新」，打开时拉正文 |
| 脏 | 相等 | 入 outbox 推送 |
| 脏 | 不等 | 标记「可能冲突」，打开时精判 |
| 墓碑标记 | 远端存在且版本变新 | 冲突（本地删 / 远端改） |
| 任意 | `deleted = true` | 本地干净 → 删除；本地脏 → 冲突 |

### 3.4 打开即拉取

```
打开笔记：
  if !cached or remote_version != base_version:
      GET /notes/{id}（或 /posts/{id}）
      remote_hash = hash(title, content, tags, folderId, pinned, publishStatus)
      比较 local_hash / base_hash / remote_hash（见 §6.1）
  else:
      直接用本地缓存
```

- 拉取后若 `remote_hash == local_hash` → 静默对齐，只更新 `base_version` / `base_hash`；
- 拉取与打开并行，编辑区先展示缓存内容（如有），避免白屏；
- 并发保护：拉取返回的 `version` 与 manifest 不一致时，以详情为准重新判定。

### 3.5 保存推送

```
输入防抖 800ms → 写本地（dirty=1, local_hash 更新）
  → PUT /api/desktop/notes/{id}（或 /posts/{id}）
      body: { version: base_version, title, content, tags, folderId, pinned }
      200 → base_version = remote_version = 新 version, dirty=0, base_hash=local_hash
      409 VERSION_CONFLICT → 拉一次详情 → 冲突状态（M1：整篇二选一；M3 加 diff）
      401 → 提示重新登录
      网络错误 → 保留 dirty，状态「离线（本地已保存）」，下次编辑 / 同步重试
```

- 文章（`POST`）推送前会 `GET` 一次详情，把 `summary / slug / coverAttachmentId / authoredAt` 原样回传，避免误清空；
- 推送使用 `base_version`（不是刚拉到的版本），否则会绕过冲突检测；
- 冲突处理：`以本地为准` = 用服务端最新 `version` 强制覆盖；`以服务端为准` = 用详情覆盖本地并更新基线；
- 切换笔记前会先 flush 未保存的编辑（仍受 800ms 防抖约束）；
- 关闭窗口前 flush 本地写入（推送可留待下次启动）。

### 3.6 删除

- 本地删除：打本地墓碑（`deleted=1, dirty=1`）→ `DELETE /api/desktop/notes|posts/{id}`；失败或离线写入 outbox，下次启动 / 同步 / 手动重试时补发；
- 服务端删除：manifest `deleted=true` 写入 `remote_deleted=1`；本地干净 → 不可见；本地有修改 → 保留为冲突行（列表角标「冲突」），可选「保留为本地副本」（复制到 `scope=local`）或「放弃本地修改」；
- 墓碑保留在本地，不参与列表；回收站 / 永久删除仍在服务端处理。

### 3.7 文件夹

- 全量拉取 `GET /api/desktop/note-folders`（量小，无需游标），本地保留 dirty 行不被覆盖；
- 本地镜像保存 `createTime`，排序与 admin 一致：`sort ASC, create_time DESC`；
- 在线新建（服务端生成 id）；改名 / 删除先落本地，再推送，失败进 outbox；
- 删除后该文件夹下笔记由服务端置为「未分类」，下次 manifest 同步跟进；
- 冲突策略：v1 以服务端为准（文件夹是低频、低风险数据）；桌面端 dirty 行在同步时保留本地名称；
- 桌面端可修改笔记的 `folderId`（编辑器内切换），本地先置 dirty；manifest 同步遇到 dirty 行时保留本地 `folder_id`，避免离线归类被远端打回；
- 侧栏列表 = 虚拟分组「全部笔记 / 未分类 / 本地文件」+ 服务端文件夹；`noteCount` 在本地按 `note_local` 聚合（`kind=NOTE` 且未删除），不额外请求服务端；
- 虚拟分组（`''` / `uncategorized` / `local-files`）不写入 `folder_local`、不参与同步。

### 3.8 文章发布（桌面端不执行）

- **发布 / 更新发布 / 下架只在 admin 完成**：摘要、slug、封面、预览、校验（`summary` 非空、`slug` 规则）与发布快照都在 admin，桌面端不复制这套表单；
- 桌面端只同步草稿，并展示 `publishStatus` + `hasUnpublishedChanges`；已发布文章仍可在桌面编辑草稿，保存后提示「有未发布更改」；
- 「管理端发布」按钮：系统浏览器打开 `<server>/admin/posts/{id}`；管理端地址默认由后端地址推导，部署形态不同可用 `SIMPLE_NOTES_ADMIN_BASE` 覆盖；
- 发布 / 下架会推进服务端 `version`；桌面保存遇到 409 时先比较内容 hash，**内容一致则静默对齐版本**，避免发布引起的假冲突。

### 3.9 离线新建（M3）

```
新建 → 本地生成 UUID（v4）→ 写入 note_local（dirty=1, content_loaded=1）
     → POST /api/desktop/notes|posts { id, title, content, ... }
        200 → MarkNoteCreateSynced（remote/base version = 返回 version）
        网络 / 5xx / 401 → outbox(item=note, op=create, payload={kind, body})
        409 → 视为上一次已成功：GET 详情 → 对齐版本
```

- 服务端 `CreateNoteRequest` / `CreatePostRequest` 接受可选 `id`（UUID 校验），已存在返回 409；
- 正文校验由 `@NotBlank` 放宽为 `@NotNull`：允许空白笔记（新建后立即开始写）；
- outbox 重放成功后若本地内容已再次变化，会追加一条 `update` 操作继续推送；
- `RemoveOutbox` 带 `op` 条件，避免把同 id 新入队的 `update` 误删；
- 离线新建的笔记仍位于当前 scope；冲突副本等「纯本地」笔记使用 `scope=local`，也会进入列表并标「本地」。

## 4. 本地数据模型（SQLite）

```sql
-- 数据归属；当前只允许一个远端 scope 处于激活状态
create table sync_scope (
    scope_id    text primary key,           -- 'local' | 'srv:<id>:user:<id>'
    server_url  text,                       -- 仅远端 scope
    user_id     text,
    username    text,
    active      integer not null default 0,
    created_at  integer not null
);

create table note_local (
    scope_id              text    not null,
    id                    text    not null,
    kind                  text    not null, -- NOTE | POST
    title                 text    not null,
    content               text,             -- null = 未缓存
    content_loaded        integer not null default 0,
    folder_id             text,
    pinned                integer not null default 0,
    tags_json             text    not null default '[]',
    publish_status        text,
    has_unpublished_changes integer,
    remote_version        integer,
    base_version          integer,
    local_hash            text,             -- 未缓存为 null
    base_hash             text,
    dirty                 integer not null default 0,
    deleted               integer not null default 0, -- 本地墓碑
    updated_at            integer not null, -- epoch ms（本地编辑时间）
    synced_at             integer,
    primary key (scope_id, id)
);
create index idx_note_local_scope_updated on note_local(scope_id, updated_at desc);
create index idx_note_local_dirty on note_local(scope_id, dirty);

create table folder_local (
    scope_id     text not null,
    id           text not null,
    name         text not null,
    sort         integer not null default 0,
    dirty        integer not null default 0,
    deleted      integer not null default 0,
    updated_at   integer not null,
    primary key (scope_id, id)
);

create table outbox (
    scope_id      text not null,
    id            text not null,            -- 内容 id 或 folder id
    entity        text not null,            -- note | folder | publish
    op            text not null,            -- upsert | delete
    payload       text not null,            -- JSON
    retry_count   integer not null default 0,
    next_retry_at integer not null,
    created_at    integer not null,
    primary key (scope_id, entity, id)
);
```

约定：

- 正文只在 `content_loaded = 1` 时有值；存根只有标题 / 文件夹 / 版本；
- 纯本地笔记：`scope_id = 'local'`，`remote_version = null`，永不入 outbox；
- 令牌沿用现有 `store`（SQLite），不落 `note_local`；
- `hash` 算法：同步字段 → 规范化 JSON（键序固定；正文 CRLF→LF；标签去重排序）→ SHA-256。两端只需桌面端一致；服务端 v1 不参与。
- 本地文件相关表（`recent_file`）与保留文件夹 `local-files` 见 `docs/desktop-local-file.md`；它们不参与同步。

## 5. 接口契约改动

| 改动 | 说明 | 状态 |
| --- | --- | --- |
| `GET /api/desktop/notes/sync`、`GET /api/desktop/posts/sync` | manifest + 游标 + 墓碑；权限 `desktop:note` / `desktop:post` | ✅ M0 |
| `GET /api/desktop/notes/{id}`、`GET /api/desktop/posts/{id}` | 打开即拉取正文 | ✅ M0 |
| `GET /api/desktop/note-folders` | 文件夹镜像 | ✅ M0 |
| `PUT /api/desktop/notes/{id}`、`PUT /api/desktop/posts/{id}` | 草稿编辑；`version` CAS，409 `VERSION_CONFLICT`；权限 `desktop:note:update` / `desktop:post:update` | ✅ M1 |
| `DELETE /api/desktop/notes/{id}`、`DELETE /api/desktop/posts/{id}` | 软删除；权限 `desktop:note:delete` / `desktop:post:delete` | ✅ M2 |
| `POST/PUT/DELETE /api/desktop/note-folders` | 文件夹新建 / 改名 / 删除；权限 `desktop:folder:write` | ✅ M2 |
| `POST /api/desktop/notes`、`POST /api/desktop/posts` | 新建（接受客户端 UUID，允许空白正文）；权限 `desktop:note:create` / `desktop:post:create` | ✅ M3 |
| `V13`–`V16` | desktop 读 / 写 / 删除 / 文件夹 / 新建权限节点（只追加） | ✅ |
| `NoteFolderResponse` 增 `updateTime` | 文件夹 LWW 判定 | ✅ M0 |
| `PUT /api/desktop/notes/{id}`、`POST`、`DELETE` | 编辑 / 新建 / 删除；`version` CAS | M1–M2 |
| `CreateNoteRequest` / `CreatePostRequest` 接受可选 `id` | 离线新建，客户端 UUID | M3 |
| `pnpm gen:api` | 契约变更后重新生成 `packages/api` | 每次 |

不改动：`PUT` 的 `version` 语义、发布快照、JWT 会话、admin 路径域。

> 权限模型：桌面端是独立入口（`entry:desktop`），内容权限单独成码，不复用 `admin:*`，避免把桌面端与 admin 角色耦合。超管自动放行。

## 6. 冲突处理

### 6.1 判定

```
local_hash = hash(本地字段)
remote_hash = hash(服务端字段)
base_hash = hash(上次同步的字段)

local_hash == remote_hash                     → 无冲突（对齐版本）
local_hash != base_hash 且 remote_hash == base_hash → 本地改过 → 自动推送
local_hash == base_hash 且 remote_hash != base_hash → 远端改过 → 自动拉取
local_hash != remote_hash 且均 != base_hash  → 冲突
```

### 6.2 UI

- 列表角标「冲突」；同步中心汇总；
- 弹窗：左「本地」右「服务端」，中部文本 diff（行级 LCS，红 = 服务端独有，绿 = 本地独有）；M3 已接入「查看差异」；
- 字段级对照：标题、文件夹、置顶、标签、发布状态；
- 操作：`以本地为准` / `以服务端为准` / `稍后处理`；
- 覆盖前把被覆盖的一侧写入**冲突副本**：`标题（冲突副本）`，`scope=local`，列表中标「本地」；编辑冲突与删除冲突都适用；
- 选择本地：`PUT` 携带服务端最新 `version`（强制覆盖）；选择服务端：本地覆盖并更新 `base_*`。

### 6.3 边界

- 仅本地删除 / 仅远端删除：按 §3.6，不弹对比，必要时提示；
- 纯本地笔记无远端版本，永不冲突；
- 批量「全部以服务端为准」v1 不提供（风险高），仅单篇处理。

## 7. 账号与服务器

- 设置页维护一个服务器地址 + 一个账号；地址校验：仅 `http/https`、去尾斜杠、禁止 URL 内嵌凭据；`http` 给明文传输提示；
- 登录：`POST /api/auth/login { username, password, client: "DESKTOP" }` → 令牌入 SQLite；`401` 统一回登录页；
- 切换账号 / 服务器：旧的 scope 置 `active = 0`，数据保留但列表过滤；当前 scope 显示 `srv:...`，纯本地笔记始终显示并带「本地」标记；
- 首次登录后若本地已有同 scope 数据，直接进入增量同步，不重建。

## 8. 离线队列

- outbox 按 `(entity, id)` 去重，保存只留最新 payload；
- 重试时机：启动 / 同步前自动 flush，手动「重试」按钮；
- 失败指数退避（2s 起，上限 5 分钟），`401` / `409` 不重试；
- 队列内容只存在本地，不上传日志，不写令牌。

## 9. 安全与约束

- WebView 不直连后端：所有请求经 Go `api.Do`，令牌不进 JS / localStorage；
- 大载荷不走 IPC：v1 不同步附件二进制；未来附件走 Go 文件流 / 自定义协议；
- 服务端地址由用户输入 → 只允许 http(s)，Go 侧做超时与响应体上限（现有 8MB 限制保留）；
- 纯本地笔记跨账号可见是**已确认的产品决定**，UI 必须有「本地」标识，避免误判为已同步。

## 10. 验收用例

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 首次登录、空库 | 列表出现标题/文件夹，**不发详情请求** |
| 2 | 打开未缓存笔记 | 1 次详情请求，内容与 Web 端一致 |
| 3 | 离线打开已缓存笔记 | 可读可编辑；未缓存显示「需联网打开」 |
| 4 | 本地编辑 → 自动保存 | 状态依次：已保存本地 → 同步中 → 已同步 |
| 5 | 两端同时改同一篇 | 打开弹冲突；选本地后服务端被覆盖且版本前进 |
| 6 | 冲突选服务端 | 本地被覆盖 + 生成冲突副本，可恢复 |
| 7 | 服务端删除、本地未改 | 本地移除，无弹窗 |
| 8 | 服务端删除、本地改过 | 冲突，可选「保留为本地副本」 |
| 9 | 本地删除未联网 | 重启后补发删除；Web 端消失 |
| 10 | 离线新建 | 联网后推送成功，id 不变，Web 端可见 |
| 11 | 保存遇到 409 | 不丢内容，进入冲突流程 |
| 12 | 文件夹重命名 / 删除 | 桌面端跟进；删除后笔记回「未分类」 |
| 13 | 切换账号 | 只显示新 scope + 纯本地笔记；旧数据隐藏 |
| 14 | 文章草稿编辑 | 不自动发布；发布按钮显式调用并更新 `hasUnpublishedChanges` |

## 11. 里程碑

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 后端 desktop 域（sync manifest / 详情 / 文件夹）+ 权限码；桌面登录 / 索引同步 / 列表 / 打开即拉取 / 只读预览 | ✅ 2026-10-31 |
| M1 | 本地编辑（Vditor）+ `version` CAS 推送 + 409 处理 + 未同步 / 冲突标记 | ✅ 2026-10-31 |
| M2 | 文件夹增删改 + 删除墓碑传播 + outbox 重试 + 删除冲突 | ✅ 2026-10-31 |
| M3 | 离线新建（客户端 UUID）+ 冲突 diff 弹窗 + 副本 | ✅ 2026-10-31 |
| M4 | 发布状态展示 + 管理端深链 + 冲突静默对齐 | ✅ 2026-10-31（发布 / 下架留在 admin） |
| M4 | 三平台实机验收（Linux / macOS、输入法、HiDPI）+ 启动 / 内存打磨 | 待做 |

## 12. 已知限制与后续

- 未缓存正文离线不可读（已确认接受）；如需改善，加后台预取与 LRU 缓存；
- 全文搜索：在线走服务端 `keyword`，离线仅标题 + 已缓存正文；
- 服务端无 `content_hash` 时，内容相同但版本不同的场景要拉正文才能确认（低频，可接受）；
- 文件夹冲突采用 LWW（服务端优先），如需严格需补 `update_time` / `version`；
- 附件离线、发布历史、字段级合并、多账号同时在线属于 M4 之后；
- 打开本地 Markdown 文件 / 临时编辑 / 导入为笔记：独立设计见 `docs/desktop-local-file.md`，导入后复用本文的 `scope` / outbox / 冲突协议。

## 13. 变更记录

| 日期 | 版本 | 说明 |
| --- | --- | --- |
| 2026-10-31 | v1 草案 | 初稿：懒加载 manifest + `version` 修订号 + 本地 hash；文件夹同步 / 标签随笔记；整篇冲突 + diff + 副本；离线新建；单服务器单账号隔离；附件 v1 不缓存 |
| 2026-10-31 | v1.1 | 落地 M0：新增 `/api/desktop/**` 域与 `desktop:note` / `desktop:post` 权限（V13）；manifest 去掉 `hasUnpublishedChanges`；`NoteFolderResponse` 增 `updateTime`；桌面端完成登录、索引同步、按需拉正文与只读预览 |
| 2026-10-31 | v1.2 | 落地 M1：`PUT /api/desktop/notes|posts/{id}` + `desktop:*:update`（V14）；桌面端 Vditor 本地编辑、800ms 防抖推送、`version` CAS、409 冲突横幅与整篇二选一；已用真实后端验证「本地保存 → 服务端改版 → 冲突 → 以本地为准」全链路 |
| 2026-10-31 | v1.3 | 落地 M2：删除接口与墓碑（`remote_deleted` 区分本地待删 / 远端已删）、删除冲突「保留本地副本 / 放弃本地」、文件夹新建 / 改名 / 删除、outbox + 指数退避 + 手动重试（V15）；已验证在线增删改、离线改名入队并在启动时重放 |
| 2026-10-31 | v1.4 | 落地 M3：`POST /api/desktop/notes|posts` 接受客户端 UUID、正文允许空白（V16）；离线新建 + create/update 链式重放；冲突 diff 弹窗（行级 LCS）；被覆盖一侧自动存为 `scope=local` 副本并进入列表；已验证「离线新建 → 重连创建 → 冲突 → diff → 以本地为准」 |
| 2026-10-31 | v1.5 | 调整 M4：发布 / 下架只在 admin（摘要 / slug / 封面 / 预览 / 校验不复刻）；桌面端展示发布状态 + 「管理端发布」深链；409 先比内容 hash，一致则静默对齐，避免发布引起的假冲突 |
