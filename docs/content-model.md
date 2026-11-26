# 内容模型设计（随手记 + 博客）

> **状态：v1.4.4 · 2026-11-19（Web 端已实现；v1.4 增加创作时间与公开列表排序上下文；v1.4.1 补充附件存储设计指针；v1.4.2 附件存储实现；v1.4.3 按实现对齐 API / 权限 / 媒体与博客运行时描述；v1.4.4 文档瘦身：权限码归并为 4 组完整码表并与 `Perms` 对账、附件 / 方言细节改为指针）**
> 范围：随手记与博客文章的统一内容模型、发布快照、数据表草案、权限码、API 契约与 Web 端范围
> 关联：`docs/auth-rbac.md`（权限体系）、`docs/markdown-dialect.md`（Markdown 方言）、`docs/tech-stack.md`（技术选型）
> 非目标：评论、桌面端同步、SEO / SSR、多作者、定时发布、分类 / 系列、笔记分享 —— 见 §13

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 表结构 | **单表 `note` + `kind`**：`NOTE` = 随手记，`POST` = 博客文章 |
| 类型转换 | 只允许 `NOTE → POST`（显式 `promote`）；**不提供 `POST → NOTE`** |
| 发布模型 | **发布快照**：`note.content` 是工作副本，公开博客读取 `note_revision` 快照 |
| 编辑已发布文章 | 草稿修改不直接影响线上；必须再次执行「更新发布」才生成新快照 |
| 下架 | `publish_status = UNPUBLISHED`，公开页不可见；文章仍保留在后台 |
| 删除 | 软删除；已发布文章删除时自动下架；恢复后回到 `UNPUBLISHED` |
| 评论 | v1 不做；后续使用**独立评论表**，不进入 `note` 主表 |
| 桌面端 | v1 不做；数据模型预留 UUID、`version` 等同步友好字段 |
| SEO | 个人项目，v1 不追求搜索流量；不做 SSR / 预渲染，只保留基础 meta |
| 作者 | v1 单作者；`owner_id` 预留多用户 / 多作者扩展 |

核心判断：

> **博客在“内容字段”上是记事本的超集，但在“生命周期、公开性、权限和接口”上不是。**
> 单表只是存储复用，不能因此把发布状态、公开查询和权限也合并成一套。

## 1. 目标与非目标

### 1.1 目标

1. 随手记与博客文章共用一套 Markdown 内容、标签、搜索、附件与编辑体验。
2. 随手记默认私有；只有博客文章可以发布，且发布必须经过明确的状态迁移。
3. 公开博客只读「最后一次发布」的快照，草稿编辑不会影响线上内容。
4. 数据层共存，API / 权限 / DTO / 页面层保持清晰边界。
5. 为桌面端同步预留数据结构（协议见 `docs/desktop-sync.md`）。

### 1.2 非目标（v1）

评论 / 点赞 / 订阅 / 阅读量；SSR 与复杂 SEO；多作者 / 投稿 / 审核流；定时发布；分类 / 系列；笔记分享链接；中文全文分词。

## 2. 领域模型

### 2.1 两种内容

| 概念 | `kind` | 说明 |
| --- | --- | --- |
| 随手记 | `NOTE` | 私有工作内容，默认不公开；没有发布状态、slug、摘要、封面 |
| 博客文章 | `POST` | 有草稿 / 已发布 / 已下架状态；有 slug、摘要、封面和发布快照 |

关键区别：

- `kind = POST` **不等于已公开**。博客草稿仍然是私有的，只有 `publish_status = PUBLISHED` 且存在发布快照时才公开。
- `kind = NOTE` 永远没有 `publish_status`。数据库约束和服务层都禁止记事本进入发布流程。
- 文章一旦发布，就产生外部契约（URL、分享链接、可能的评论），因此不允许随意转回记事本。

### 2.2 核心关系

```text
NOTE（私有）
   │ promote
   ▼
POST / DRAFT（私有草稿）
   │ publish
   ▼
note_revision（不可变快照）◄──── 公开博客只读这里
   ▲
   │ 编辑草稿后再次 publish
POST / PUBLISHED（有未发布更改）
   │ unpublish
   ▼
POST / UNPUBLISHED（后台保留，公开不可见）
```

### 2.3 单表边界

- **共用**：`note` 主表、标签、文件夹、附件、版本号、软删除、审计字段。
- **分开**：`NoteService` / `PostService`、admin 接口、权限码、DTO、页面菜单、公开查询条件。
- **禁止**：通用 `/api/content/{id}` 同时服务笔记和文章；公开接口不得只按 ID 查询而不校验 `kind` / `publish_status`。

## 3. 数据模型

### 3.1 主表 `note`

| 列 | 类型 / 约束 | 说明 |
| --- | --- | --- |
| `id` | `varchar(36)` PK | UUID 字符串，应用层生成 |
| `kind` | `varchar(16)` NOT NULL | `NOTE` / `POST`；创建后不可改，唯一例外是 `promote` |
| `title` | `varchar(200)` NOT NULL | 标题 |
| `content` | `text` NOT NULL | Markdown 原文，后端不渲染 |
| `summary` | `varchar(500)` NULL | 博客摘要；发布时必填，记事本必须为 NULL |
| `owner_id` | `varchar(36)` NOT NULL | FK `sys_user`；作者 / 所有者 |
| `folder_id` | `varchar(36)` NULL | FK `note_folder`；仅 `NOTE` 使用，转文章时清空 |
| `pinned` | `smallint` NOT NULL DEFAULT 0 | 置顶；公开首页的置顶属于运营字段，立即生效 |
| `slug` | `varchar(200)` NULL | 仅 `POST`；首次发布后不可修改，公开 URL 使用 |
| `cover_attachment_id` | `varchar(36)` NULL | FK `attachment`；文章封面，记事本必须为 NULL |
| `publish_status` | `varchar(16)` NULL | `DRAFT` / `PUBLISHED` / `UNPUBLISHED`；记事本必须为 NULL |
| `published_revision_id` | `varchar(36)` NULL | FK `note_revision`；当前公开快照指针 |
| `first_published_at` | `timestamptz` NULL | 首次发布时间（本站）；系统写入 |
| `last_published_at` | `timestamptz` NULL | 最近一次发布时间（本站）；系统写入 |
| `authored_at` | `timestamptz` NULL | 仅 `POST`：创作时间 / 搬运文章的原始日期；可空，公开展示优先使用，NULL 回落 `first_published_at` |
| `draft_modified_at` | `timestamptz` NULL | 草稿最近修改时间；用于判断「有未发布更改」 |
| `version` | `bigint` NOT NULL DEFAULT 0 | 乐观锁版本号；JPA `@Version` |
| 审计七件套 | 沿用 `BaseEntity` | `create_time` / `update_time` / `delete_time` / 操作人 / `delete_flag` |

说明：

- `content` 只存 Markdown，不存渲染后的 HTML，保持 `docs/markdown-dialect.md` 的约定。
- `slug` 在首次发布时生成，之后不可修改；不引入 slug 历史 / 301（后续需要再设计）。
- `pinned` 不参与发布快照：置顶是首页运营行为，允许立即生效。
- `authored_at` 是运营 / 展示字段，不参与发布快照：修改立即影响公开展示、列表排序与归档，**不触发「有未发布更改」**；笔记必须为 `NULL`（数据库约束 + Service 校验）。
- 公开文章日期统一为 `displayAt = authoredAt ?? firstPublishedAt`（计算值，不落库）：展示、默认排序、归档分组都使用它；`firstPublishedAt` / `lastPublishedAt` 仍表示本站的真实发布事件。
- 不提供“归档”：随手记定位云端记事本，修改内容是常态；收纳需求由软删除 + 回收站覆盖。文章下架仍用 `publish_status = UNPUBLISHED`。
- `version` 用于 Web 多标签页并发编辑和未来桌面同步；BaseEntity 目前没有，需在 `Note` 实体上单独声明。

### 3.2 发布快照 `note_revision`

| 列 | 类型 / 约束 | 说明 |
| --- | --- | --- |
| `id` | `varchar(36)` PK | UUID |
| `note_id` | `varchar(36)` NOT NULL | FK `note` |
| `revision_no` | `int` NOT NULL | 同一文章内递增；与 `note_id` 唯一 |
| `title` | `varchar(200)` NOT NULL | 发布时的标题 |
| `content` | `text` NOT NULL | 发布时的 Markdown |
| `summary` | `varchar(500)` NOT NULL | 发布时的摘要 |
| `cover_attachment_id` | `varchar(36)` NULL | 发布时的封面 |
| `create_by` | `varchar(36)` | 发布人 |
| `create_time` | `timestamptz` NOT NULL | 发布时间 |

特性：

- **不可变**：只 INSERT，不 UPDATE / DELETE；不参与软删除。
- 不保存 slug、`pinned`、`allow_comment` 等运营字段；slug 在首次发布后稳定，置顶是运营行为。
- 标签单独快照到 `note_revision_tag`。
- 版本历史 UI 与回滚属于 P1；v1 只需保证快照表存在，公开读取走快照。
- 后续如需 SEO 快照，在 revision 上追加字段即可，不改公开读取模型。

### 3.3 标签

| 表 | 说明 |
| --- | --- |
| `tag` | 标签主表：`id`、`name`、审计字段；`lower(name)` 唯一（`delete_flag = 0`） |
| `note_tag` | 当前草稿标签：`(note_id, tag_id)` 复合主键；保存时全量替换，物理删除 |
| `note_revision_tag` | 发布快照标签：`(revision_id, tag_id)` 复合主键；发布时复制，不可变 |

规则：

- 标签在笔记和文章之间共用；笔记标签只对笔记所有者可见，公开博客只读 `note_revision_tag`。
- 编辑已发布文章的标签后，公开标签不变，直到「更新发布」。
- **标签管理（v1.1）**：标签是全局分类，提供独立管理页；编辑器标签下拉从全局标签联想复用，同时保留输入新名称创建。
- **改名 / 删除是全局操作**：因为 `note_revision_tag` 只存 `tag_id`，改名 / 删除会立即影响所有当前内容和已发布快照的标签展示；管理页必须展示使用量并二次确认。
- **删除语义**：标签软删除；清理当前 `note_tag` 关联，保留 `note_revision_tag` 历史关联但公开查询过滤已删除标签；有使用量时后端要求 `force=true`。
- **从标签管理页分配内容**：只写当前草稿；已发布文章需在文章编辑页「更新发布」后线上标签才变化；移除同理，线上快照仍使用直到更新发布。
- 使用量分三层：随手记数、博客草稿数、已发布快照数；关联内容列表同时标记“草稿 / 线上快照 / 待更新发布”。

### 3.4 文件夹 `note_folder`

| 列 | 说明 |
| --- | --- |
| `id` | `varchar(36)` PK |
| `owner_id` | FK `sys_user` |
| `parent_id` | `varchar(36)` NULL；父文件夹，根为 NULL（v2.0） |
| `name` | `varchar(64)` NOT NULL |
| `sort` | `int` NOT NULL DEFAULT 0 |
| 审计七件套 | 标准软删除 |

- v2.0 起为**树形**：根为第 1 级，最多 5 级；不支持移动文件夹（改父级），只支持新建子文件夹。
- 同一所有者、**同一父级**下 `lower(name)` 唯一（`delete_flag = 0`，`coalesce(parent_id, '')`）；**不同层级允许同名**。
- 删除文件夹不删除笔记，笔记回到「未分类」；**存在子文件夹时禁止删除**（先处理子级）。
- `folder_id` 只对 `NOTE` 有意义；`promote` 时清空。
- 文件夹接口返回 `noteCount`（该文件夹**子树**含自身的未删除随手记数量），仅用于列表统计，不参与新建 / 编辑请求。
- 列表筛选参数 `folderId` 表示「该文件夹及所有子孙」；`folderId=uncategorized` 表示「未分类」（`folder_id IS NULL`），是查询保留值，不对应任何 `note_folder` 行。
- 迁移：`V18__folder_tree.sql` 新增 `parent_id`，唯一索引改为 `(owner_id, coalesce(parent_id, ''), lower(name))`；历史文件夹全部作为根。

### 3.5 附件 `attachment`

| 列 | 说明 |
| --- | --- |
| `id` | `varchar(36)` PK |
| `owner_id` | FK `sys_user` |
| `storage_mode` | `LOCAL` / `OSS` / `DATABASE`；记录该行二进制实际所在介质（V11 起） |
| `storage_key` | 存储介质内的对象 key，全表唯一 |
| `original_name` | 原始文件名 |
| `mime_type` | MIME |
| `size_bytes` | 文件大小 |
| `width` / `height` | 图片尺寸，可空 |
| `alt_text` | 封面等场景的默认 alt，可空 |
| 审计七件套 | 标准软删除（`delete_flag`），无独立 `status` 列 |

- 正文图片使用可访问 URL（DIRECT / 稳定端点兜底）、封面 `cover_attachment_id`、删除前检查封面 / 快照引用且不做正文反向解析（孤儿清理放 P1）：**语义与链路以 `docs/attachment-storage.md` 为准**（§4 表结构、§7.1–§7.2 访问路径与代理、§9.3 删除、§10 安全、§8 上传校验），本文不重复。

### 3.6 评论（后续，不在 V2 建表）

- 评论是独立领域，不放进 `note`。
- 预计表：`post_comment(id, post_id, parent_id, author_name, author_email, content, status, ip_hash, create_time, ...)`。
- 评论关联文章（`note.id`），不关联 revision；文章换快照后评论保留。
- 公开评论只对 `publish_status = PUBLISHED` 的文章开放；下架 / 删除时隐藏。
- 认证、匿名、审核、反垃圾等随评论设计单独定稿。

### 3.7 约束与索引

建表以 Flyway 为准（`backend/src/main/resources/db/migration/V2__init_content.sql`）。约束表达的是不变量，改 schema 时不得放松：

- `kind` 只有 `NOTE` / `POST`，创建后不可改（唯一例外是 `promote`）。
- `NOTE` 不得有摘要 / slug / 封面 / 发布状态 / 发布时间 / `authored_at` / `draft_modified_at`；`POST` 不得有 `folder_id`，且必须有 `publish_status`。
- `DRAFT` 不得有 `published_revision_id`、首次 / 最近发布时间。
- `PUBLISHED` 必须有 `published_revision_id`，且快照属于同一篇 `note`。
- `slug` 在未删除内容内唯一；`(note_id, revision_no)` 唯一。
- 公开排序用 `displayAt = coalesce(authored_at, first_published_at)`，索引形式以迁移为准，本文不复述。

### 3.8 表例外汇总

| 表 | 例外 |
| --- | --- |
| `note_revision` | 不可变发布事实，只 INSERT；无 `update_time` / 软删除 |
| `note_tag` | 全量替换式关联表，物理删除；仅 `create_by` / `create_time` |
| `note_revision_tag` | 不可变快照关联，无审计字段 |
| `note` slug 唯一索引 | 永久唯一，不带 `delete_flag = 0` |
| `attachment` | 软删除，但删除前需检查封面 / 快照引用 |

## 4. 发布模型

### 4.1 草稿与线上快照

- `note` 上的 `title` / `content` / `summary` / `cover_attachment_id` / 标签是**当前草稿**。
- `note_revision` 是**线上版本**；公开博客只读 revision。
- 编辑已发布文章时，草稿立即保存，但线上不变；后台显示「有未发布更改」。
- 点击「更新发布」后，新建 revision 并移动 `published_revision_id` 指针，线上才变化。
- 已发布文章的 slug 只读；摘要 / 封面 / 标题 / 正文 / 标签可继续编辑。摘要不能清空（已发布 / 已下架状态要求摘要非空，服务层提前校验）。

### 4.2 状态机

```text
NOTE:
  未删除 ⇄ TRASH（delete / restore）

POST:
  DRAFT ── publish ──> PUBLISHED
    ▲                    │  │
    │                    │  ├── 编辑草稿 ──> PUBLISHED（有未发布更改；线上仍旧快照）
    │                    │  └── unpublish ──> UNPUBLISHED
    │                    │                         │
    │                    └──── 更新发布 ───────────┘
    │                                              │
    └────────────── 无快照时恢复为 DRAFT ◄─────────┘

  POST 任意状态 ── delete ──> TRASH
  TRASH ── restore ──> DRAFT（从未发布） / UNPUBLISHED（已发布过）
```

### 4.3 操作矩阵

| 操作 | 前置状态 | 结果 |
| --- | --- | --- |
| 新建 | — | `kind=NOTE`，私有 |
| `promote` | `NOTE`，且未删除 | `kind=POST`、`publish_status=DRAFT`；清空 `folder_id`；更新 `draft_modified_at`；保留标题 / 正文 / 标签；slug / 摘要 / 封面由用户后续填写 |
| 保存草稿 | `NOTE` / `POST` 任意 | 更新草稿字段；文章额外更新 `draft_modified_at=now`；`version++` |
| 首次发布 | `POST / DRAFT` | 生成 slug（如为空）；创建 revision；`PUBLISHED`；设置首次 / 最近发布时间 |
| 更新发布 | `POST / PUBLISHED` 且有未发布更改 | 创建新 revision；移动指针；更新最近发布时间 |
| 无更改再次发布 | `POST / PUBLISHED` 且无更改 | 返回 `NO_CHANGES_TO_PUBLISH`（409） |
| 下架 | `POST / PUBLISHED` | `UNPUBLISHED`；保留 revision 指针；公开不可见 |
| 重新发布（无更改） | `POST / UNPUBLISHED` 且已有快照 | 复用快照指针；`PUBLISHED`；更新最近发布时间 |
| 重新发布（有更改） | `POST / UNPUBLISHED` 且有未发布更改 | 创建新 revision；`PUBLISHED` |
| 删除 | 任意未删除内容 | 软删除；文章若已发布先置 `UNPUBLISHED` |
| 恢复 | 回收站 | 笔记恢复未删除；文章恢复为 `UNPUBLISHED` |
| 转回笔记 | — | **不提供** |

### 4.4 发布事务

`PublishService.publish(postId, version)`：

1. 加载 `note`，校验 `kind=POST`、未删除；校验 `version` 与请求一致（乐观锁）。
2. 校验状态转换：`DRAFT` / `PUBLISHED` / `UNPUBLISHED` 可发布；`PUBLISHED` 无更改则拒绝。
3. 校验发布字段：
   - `title` 非空；
   - `content` 非空；
   - `summary` 非空（Web 端从正文纯文本自动生成初稿，允许编辑）；
   - `slug` 合法且唯一；为空时生成默认值；
   - 标签数量 / 长度限制（按实现定，建议不超过 10 个）。
4. 计算 `revision_no = max(revision_no) + 1`。
5. 插入 `note_revision`，复制当前 `note_tag` 到 `note_revision_tag`。
6. 更新 `note`：
   - `published_revision_id = newRevision.id`；
   - `publish_status = PUBLISHED`；
   - `slug`（仅首次发布写入）；
   - `first_published_at`（仅首次）；
   - `last_published_at = now()`。
   - `authored_at` 不随发布改写：它是可编辑的展示日期，首次发布只写 `first_published_at`。
7. 返回后台文章详情（含公开 URL、快照信息）。

并发说明：同一文章的发布串行化由 `version` + 唯一约束 `(note_id, revision_no)` 共同兜底；冲突返回 409。

发布请求只接受 `version`（不重复提交正文 / 标题 / 摘要 / slug）；发布内容必须来自已保存的草稿，避免“发布弹窗里的临时值”和草稿不一致。前端在点击发布前先保存草稿。

### 4.5 下架、删除与恢复

- **下架**：只改 `publish_status`，保留 `published_revision_id` 和 slug；公开详情 / 列表 / 搜索 / RSS 全部不可见。
- **删除**：软删除；若 `PUBLISHED` 先下架；`published_revision_id` 保留。
- **恢复**：
  - 有 `published_revision_id`（曾经发布过）→ `UNPUBLISHED`，绝不自动重新公开；
  - 无快照（纯草稿）→ `DRAFT`。
- **永久删除**：v1 不做；P1 再设计 revision / 标签 / 附件的清理策略。

### 4.6 slug 规则

- 首次发布时若为空，生成默认值：`yyyyMMdd-<8 位短随机串>`，例如 `20261023-a1b2c3d4`；用户可在发布前改成可读的英文 slug。
- 允许字符：小写字母、数字、连字符，正则 `^[a-z0-9]+(-[a-z0-9]+)*$`。
- 首次发布后**不可修改**；v1 不提供 slug 历史和 301。若未来需要改名，另做 `post_slug_history` 与重定向设计。
- 删除后的 slug 永久保留，不复用；公开 URL 使用 `/posts/{slug}`。
- 中文标题不自动转拼音；默认 slug 使用日期 + 随机串，避免引入拼音依赖。

### 4.7 「有未发布更改」判定

对文章：

```text
hasUnpublishedChanges =
  publish_status = UNPUBLISHED
  ? (无快照 || draft_modified_at > publishedRevision.create_time)
  : (publish_status = PUBLISHED && draft_modified_at > publishedRevision.create_time)
```

- 对文章而言，任何草稿变更（标题、正文、摘要、封面、标签、slug）都必须更新 `draft_modified_at`；标签关联变化时由 Service 显式 touch `note`。笔记不使用 `draft_modified_at`（数据库约束要求为 NULL）。
- 仅修改 `pinned` 或 `authored_at` 不算内容变更，不触发「有未发布更改」。
- 前端按钮文案：`DRAFT` →「发布」；`PUBLISHED` 无更改 →「已发布」；`PUBLISHED` 有更改 →「更新发布」；`UNPUBLISHED` →「重新发布」。

## 5. 公开读取规则

公开博客是只读投影，必须同时满足：

```text
note.kind = 'POST'
note.publish_status = 'PUBLISHED'
note.published_revision_id IS NOT NULL
note.delete_flag = 0
```

数据来源：

| 公开字段 | 来源 |
| --- | --- |
| 标题 / 正文 / 摘要 / 封面 | `note_revision` |
| 标签 | `note_revision_tag` + `tag`（过滤已删除标签） |
| slug | `note.slug`（首次发布后稳定） |
| 作者 | `note.owner_id` → `sys_user.nickname` |
| 首次 / 最近发布时间 | `note.first_published_at` / `last_published_at` |
| 创作时间 | `note.authored_at`（可空，运营字段） |
| 展示日期 | `displayAt = coalesce(note.authored_at, note.first_published_at)`（计算值）；公开列表、详情、归档统一使用 |
| 置顶 | `note.pinned`（运营字段，立即生效） |

规则：

- 草稿、`UNPUBLISHED`、已删除文章一律 404；不在公开接口暴露“存在但未发布”的信息。
- v1 不提供公开的草稿**文章**预览 token；后台编辑器内的预览使用当前草稿，并明确提示“线上仍是已发布版本”。主题预览 token 只用于“看未激活的主题版本”（见 `docs/blog-runtime-theme.md` §7.2），不携带文章身份。
- 公开列表不返回正文；详情返回快照 Markdown。
- 公开列表 / 搜索 / 归档 / 详情上下篇统一支持排序参数 `pinnedFirst` / `direction` / `sortBy`，规则见 §6.4。
- 公开搜索只搜索已发布 revision 的标题 / 正文；不得命中草稿内容。
- RSS / sitemap（如实现）只包含已发布文章。
- 公开响应不得包含 `owner_id`、内部状态、审计字段、附件 storage key 等内部信息；作者只暴露昵称 / 展示名。

## 6. API 契约

> 端点、字段与 DTO 以 OpenAPI（`/v3/api-docs`）为唯一来源；本节只记语义与边界，生成类型在 `packages/api`。

### 6.1 随手记（`/api/admin/notes`）

- CRUD + 文件夹 + 标签 + 置顶 + 回收站 / 恢复；列表不含正文，返回 `version`。
- `promote` 转为博客草稿，使用 `admin:post:create`；结果是一篇新文章，原笔记不再出现在笔记列表；**没有反向接口**。

### 6.2 博客文章（`/api/admin/posts`）

- 列表支持 `keyword` / `status` / `hasUnpublishedChanges` 过滤；详情返回当前草稿 + 已发布快照摘要 + `hasUnpublishedChanges` + 公开 URL。
- `PUT` 只更新草稿：不接受 `kind` / `publish_status` / `published_revision_id`；更新与发布都带 `version`，冲突 409。
- `publish` 首次发布 / 更新发布；`unpublish` 下架；删除已发布文章先下架。
- `authoredAt`：`null` 不修改，空串清除；不是内容变更，不改 `draft_modified_at`，不要求「更新发布」；笔记写入被拒。

### 6.3 标签（`/api/admin/tags`）

- 改名 / 删除是全局操作，会影响所有当前内容与已发布快照的展示；有使用量时删除需 `force=true`，否则 409 `TAG_IN_USE`。
- 给已发布文章加 / 删标签只写当前草稿，线上要「更新发布」后才变化；使用量分三层展示（笔记 / 草稿 / 快照）。

### 6.4 公开博客（`/api/blog/*`）

- 匿名只读发布快照；`entry:blog` 不参与公开读取。
- 排序参数 `pinnedFirst` / `direction` / `sortBy`：`sortBy` 白名单 `displayAt` / `firstPublishedAt` / `lastPublishedAt`，未知值 400，不得拼进 SQL；`direction` 只作用于时间字段，不翻转置顶。
- `year` / `month` 跟随 `sortBy` 字段，按 UTC；归档为「年月索引 + 月内分页」，月内计数与索引同源。
- 详情 `prevPost` / `nextPost` 按同一组筛选参数计算；当前文章不在筛选集内时忽略筛选；搜索页上下篇携带 `q`。
- `feed.xml` / `sitemap.xml` 不纳入 TS 生成客户端。

### 6.5 DTO 与错误

- 列表 DTO 不含正文；公开响应不含任何内部字段。
- 请求 DTO 按场景分开（创建 / 更新 / 发布）；`kind`、`publish_status`、`published_revision_id` 不出现在普通更新请求。

| 场景 | HTTP | 语义码 |
| --- | --- | --- |
| 内容不存在 / 不可见 | 404 | `NOT_FOUND` |
| 版本冲突 | 409 | `VERSION_CONFLICT` |
| slug 已存在 | 409 | `SLUG_TAKEN` |
| 非法状态转换 | 409 | `INVALID_STATE_TRANSITION` |
| 已发布且无更改 | 409 | `NO_CHANGES_TO_PUBLISH` |
| 删除标签仍有使用量 | 409 | `TAG_IN_USE` |
| 发布字段不完整 | 400 | `PUBLISH_VALIDATION_FAILED` |
| 无权限 | 403 | `PERMISSION_DENIED` |

## 7. 权限与菜单

> **目录唯一来源：迁移脚本（`V3` / `V4` / `V12`）+ `docs/auth-rbac.md`；代码引用 `Perms` 常量，启动对账检查常量 / 注解 / `sys_menu` 三方一致，`pnpm check:docs` 对账本节码集合与 `Perms.java`。** 本节只给内容域的码与语义，不复述 `sys_menu` 行（父节点、`sort` 以迁移为准）。

| 权限码 | 说明 |
| --- | --- |
| `admin:note`、`admin:note:create`、`admin:note:update`、`admin:note:delete` | 随手记页面（含读接口）/ 新建 / 编辑（含文件夹）/ 删除（含回收站、恢复） |
| `admin:post`、`admin:post:create`、`admin:post:update`、`admin:post:publish`、`admin:post:unpublish`、`admin:post:delete` | 博客管理页面 / 新建（含笔记转博客）/ 编辑草稿 / 发布与更新发布 / 下架 / 删除 |
| `admin:tag`、`admin:tag:create`、`admin:tag:update`、`admin:tag:delete`、`admin:tag:assign` | 标签管理页面 / 创建 / 改名 / 删除（有使用量需 `force`）/ 分配与移除 |
| `admin:media`、`admin:media:upload`、`admin:media:delete`、`admin:media:storage` | 媒体库页面 / 上传 / 删除 / 切换新上传存储介质 |

- 每组第一个不带后缀的码是 MENU（页面 + 读接口），其余是 ACTION。
- `promote` 使用 `admin:post:create`：结果是一篇博客草稿，而不是“修改笔记类型”。
- 记事本没有发布权限码；`admin:post:publish` 只出现在文章接口。
- 公开博客不需要权限码；未来评论使用 `blog:comment:*`。
- v1 单作者；`admin:post` 持有者可管理全部文章，`admin:note` 默认只管理自己的笔记，超管不受限。多作者 / 数据范围后续再设计。
- `admin:media` 的读权限由菜单码承担；上传 / 删除 / 切换存储介质用 ACTION。
- 编辑器联想 `GET /api/admin/tags/options` 允许 `admin:note` 或 `admin:post` 任一访问；标签管理页与写操作使用 `admin:tag:*`。
- 标签改名 / 删除是全局分类操作；删除有使用量时前端二次确认并带 `force=true`。
- 站点设置 / 主题管理的权限码（`admin:site:*`、`admin:theme:*`）见 `docs/blog-runtime-theme.md` §9.1。
- 页面注册表：`admin:note` → `/notes`、`admin:post` → `/posts`、`admin:tag` → `/tags`、`admin:media` → `/media`。
- 不新增 `admin:content` 目录：v1 直接作为一级菜单，内容模块增多后再在菜单管理里调整。

## 8. 功能清单

**v1 已实现**

- 随手记：文件夹树、标签、置顶、私有、回收站 / 恢复、一键转博客草稿（不可逆）。
- 博客创作：草稿编辑、预览、发布 / 更新发布 / 下架、发布面板（slug / 摘要 / 封面）、发布快照指针、状态徽标（草稿 / 已发布 / 有未发布更改 / 已下架）、删除与回收站。
- 博客前台：首页列表与置顶、详情（目录 / 阅读时长 / 代码复制）、标签、归档、搜索、404、响应式、暗色主题、基础 meta。
- 权限与运维：内容域权限码与菜单、公开接口严格按 `kind + publish_status + 快照 + 软删除` 过滤、上传校验与附件删除保护、发布 / 下架留痕。

**P1 / P2（未做，不作承诺）**

评论；版本历史 UI 与回滚；定时发布；标签合并 / 别名；导入导出；RSS / sitemap / JSON-LD；slug 历史与 301；孤儿附件清理；`pg_trgm`；多作者与审核流；订阅、统计、i18n、AI 摘要。

## 9. Web 信息架构

### 9.1 admin

| 路由 | 页面 | 权限 |
| --- | --- | --- |
| `/notes` | 随手记列表（文件夹 + 标签筛选） | `admin:note` |
| `/notes/:id` | 笔记编辑 | `admin:note` |
| `/posts` | 博客文章列表（状态 Tab） | `admin:post` |
| `/posts/:id` | 文章编辑 / 发布 | `admin:post` |
| `/tags` | 标签管理（使用量 / 分配内容） | `admin:tag` |
| `/media` | 媒体库 | `admin:media` |
| `/site` | 站点设置（品牌 / 作者 / 导航 / 页脚 / 独立页面） | `admin:site` |
| `/themes` | 主题管理（版本 / 预览 / 应用） | `admin:theme` |
| `/trash` | 回收站（笔记 / 文章分 Tab） | `admin:note:delete` / `admin:post:delete` |

交互要点：

- 笔记编辑器提供「转为博客」按钮（`v-perm="'admin:post:create'"`），确认后跳转到文章编辑器；**没有反向按钮**。
- 文章编辑器顶部状态徽标：`草稿` / `已发布` / `已发布 · 有未发布更改` / `已下架`。
- 发布按钮按状态变化：「发布」「更新发布」「重新发布」「已发布」。
- 文章发布后 slug 只读；预览明确标注“当前草稿预览，线上仍是已发布版本”。
- 已发布文章删除时二次确认，提示会先下架。
- 列表默认按更新时间降序；公开首页按置顶、`displayAt`（`authoredAt ?? firstPublishedAt`）降序。

### 9.2 blog 前台

| 路由 | 页面 |
| --- | --- |
| `/` | 首页文章流 / 置顶 |
| `/posts/:slug` | 文章详情 |
| `/tags/:id` | 标签文章列表 |
| `/archives` | 年月索引；`?year=&month=` 为月内分页视图 |
| `/search` | 搜索 |
| `/about` | 关于页（独立页面 `about`，可在站点设置维护） |
| `/p/:slug` | 其他独立页面 |

- 匿名可读；不要求登录。
- **页面形态（v1.3 起）**：后端组装壳（站点配置 + 主题 HTML/CSS）+ Vite 构建的 runtime（打包时进入后端 jar，同源 `/blog/**`），主题由管理端以不可变版本下发；详见 `docs/blog-runtime-theme.md`。
- Markdown 由 blog runtime 使用 **Lute** 渲染（`@simple-notes/markdown` + `method.min.js`），与 admin 共用同一份 `vditorMarkdown`；DOMPurify 在渲染前清洗；方言 v2 允许数学 / emoji / 安全行内 HTML。
- 响应式使用 `useBreakpoint()` / 统一断点值，不按 UA 判断。

## 10. Markdown、媒体与安全

> 方言规范以 `docs/markdown-dialect.md` 为准，验收样本见 `docs/markdown-dialect-fixture.md`；存储链路与上传校验以 `docs/attachment-storage.md` 为准。本节只保留内容模型特有约定。

- 笔记与文章使用同一套 Markdown 方言，不为博客单独扩展语法。
- 元数据全部走数据库字段，不使用 front matter。
- 后端只存原文，不渲染不清洗；清洗发生在所有前端渲染入口。
- 正文图片使用可访问 URL，不写 R2 endpoint / bucket；封面使用 `cover_attachment_id`。
- 媒体域是匿名入口，敏感文件不要放入公开媒体库。

## 11. 搜索与列表

- 笔记搜索：标题 / 正文 / 标签，限定当前用户（超管可全量）；默认排除 `delete_flag = 1`。
- 文章后台搜索：标题 / 摘要 / 正文草稿，限定 `kind=POST`。
- 公开搜索：只查 `PUBLISHED` 文章的发布快照，不得查草稿。
- v1 中文搜索使用 `ILIKE '%keyword%'`；数据量增长后再评估 `pg_trgm`，不引入 `zhparser`。
- 归档采用「月份索引 + 月内分页」：`/api/blog/archives` 按 `sortBy` 所选字段统计年月（默认 `displayAt = coalesce(authored_at, first_published_at)`），月内文章由 `/api/blog/posts?year=&month=&page=&size=` 分页；`year` / `month` 过滤跟随 `sortBy` 字段，年月统一按 UTC 计算，前后端保持同一口径；标签计数只统计已发布文章。
- 列表默认排序：
  - 笔记：`pinned DESC, update_time DESC`；
  - 后台文章：`update_time DESC`；
  - 公开首页 / 标签 / 搜索：`pinnedFirst=true, direction=desc, sortBy=displayAt`；
  - 公开归档索引：`pinnedFirst=false, direction=desc, sortBy=displayAt`（可由主题改 query）；
  - 公开归档月视图：在索引参数上增加 `year` / `month`，`page` / `size` 在月内分页。

## 12. 迁移

- 结构变更只追加 Flyway 脚本（`backend/src/main/resources/db/migration`）；`V1__init_auth_rbac.sql` 已冻结，不改。
- 权限节点由迁移只 INSERT `sys_menu`：父节点按 code 子查询、id 用 `gen_random_uuid()`，不手填 UUID。
- 内容域实体：`Note`、`NoteRevision`、`NoteFolder`、`Tag`、`NoteTag`、`NoteRevisionTag`、`Attachment`；枚举 `NoteKind`、`PublishStatus`；查询必须显式带 `kind`。
- Repository 用派生查询 + QueryDSL；Service 是唯一业务入口。具体类与端点见代码与 OpenAPI。

## 13. 开放问题

1. **评论**：独立表；匿名 / 登录、审核、反垃圾、通知策略待单独设计。
2. **revision 保留**：v1 全量保留；清理策略与回滚 UI 归 P1。
3. **标签治理**：合并、别名、删除后同名复用策略待评估。
4. **slug 变更**：v1 首发布后不可改；需要时补 `post_slug_history` 与 301。
5. **SEO / SSR**：v1 接受 SPA；需要搜索流量时另做预渲染设计。
6. **多作者 / 数据范围**：v1 单作者；`owner_id` 已预留，协作与隔离规则后续定。
7. **媒体隐私**：已改为私有 R2 + 媒体域匿名入口；敏感文件不应进入公开媒体库。
