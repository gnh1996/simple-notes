# 内容模型设计（随手记 + 博客）

> **状态：v1.4.2 · 2026-10-31（Web 端已实现；v1.4 增加创作时间与公开列表排序上下文；v1.4.1 补充附件存储设计指针；v1.4.2 附件存储实现）**
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

---

## 1. 目标与非目标

### 1.1 目标

1. 随手记与博客文章共用一套 Markdown 内容、标签、搜索、附件和编辑体验。
2. 随手记默认私有；只有博客文章可以发布，且发布必须经过明确的状态迁移。
3. 公开博客只读取「最后一次发布」的快照，避免草稿编辑、误操作或未来的桌面同步直接改坏线上内容。
4. 笔记 / 文章在数据层可共存，但在 API、权限、DTO、页面层保持清晰边界。
5. 为未来的桌面端离线同步预留数据结构，但不在 v1 实现同步协议。

### 1.2 v1 非目标

- 评论、点赞、订阅、阅读量；
- 桌面端本地存储与同步；
- SSR、预渲染、复杂 SEO / 结构化数据；
- 多作者 / 投稿 / 审核流；
- 定时发布；
- 分类、系列、专题；
- 笔记分享链接；
- 中文全文分词 / 复杂搜索排序。

---

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

---

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
| `name` | `varchar(64)` NOT NULL |
| `sort` | `int` NOT NULL DEFAULT 0 |
| 审计七件套 | 标准软删除 |

- v1 单层文件夹，不做嵌套。
- 同一所有者下 `lower(name)` 唯一（`delete_flag = 0`）。
- 删除文件夹不删除笔记，笔记回到「未分类」。
- `folder_id` 只对 `NOTE` 有意义；`promote` 时清空。

### 3.5 附件 `attachment`

| 列 | 说明 |
| --- | --- |
| `id` | `varchar(36)` PK |
| `owner_id` | FK `sys_user` |
| `storage_key` | R2 对象 key，唯一 |
| `original_name` | 原始文件名 |
| `mime_type` | MIME |
| `size_bytes` | 文件大小 |
| `width` / `height` | 图片尺寸，可空 |
| `alt_text` | 封面等场景的默认 alt，可空 |
| `status` | `ACTIVE` / `DELETED`，或沿用软删除 |
| 审计七件套 | 标准 |

- 正文图片使用**可访问 URL**：DIRECT 模式下 OSS 为 `/oss/{key}`、本地 / DB 为 `/api/media/{id}`；不直接写 R2 endpoint / bucket。`/api/media/{id}` 保留为稳定兜底端点（OSS 会 302 到 `/oss/{key}`）。
- 封面通过 `cover_attachment_id` 引用。
- 删除附件前检查是否被文章封面或已发布快照引用；正文内引用 v1 不做完整反向解析，孤儿清理放 P1。
- 上传限制（支持任意文件后）：单文件 20 MB；魔数校验；**禁止 SVG**（XSS 风险）；图片 inline，其他文件强制 `Content-Disposition: attachment`；图片重新编码仍待定。详见 `docs/attachment-storage.md` §8。
- 公开 bucket + 随机 UUID 的访问方式对草稿 / 任意文件不是严格隔离；个人项目可接受，后续如需严格隐私改为私有 bucket / 签名 URL。
- 实现进度：LOCAL / R2 / DATABASE 三介质已实现（`V11` / `V12`）；R2 采用私有 bucket + Nginx `auth_request` 预签名代理；`storage_key` 与 `/api/media/{id}` 稳定端点契约不变。
- 三介质扩展（LOCAL / OSS / DATABASE）设计见 `docs/attachment-storage.md`：`attachment.storage_mode` 按行记录实际介质，全局设置只影响新上传；DIRECT 模式下 OSS 返回 `/oss/{key}`，经媒体域 Nginx 访问 R2（公开 bucket 直连；私有 bucket 签名 / 预签名），本地 / DB 返回 `/api/media/{id}`，稳定端点保留为兜底。

### 3.6 评论（后续，不在 V2 建表）

- 评论是独立领域，不放进 `note`。
- 预计表：`post_comment(id, post_id, parent_id, author_name, author_email, content, status, ip_hash, create_time, ...)`。
- 评论关联文章（`note.id`），不关联 revision；文章换快照后评论保留。
- 公开评论只对 `publish_status = PUBLISHED` 的文章开放；下架 / 删除时隐藏。
- 认证、匿名、审核、反垃圾等随评论设计单独定稿。

### 3.7 约束与索引草案

```sql
-- 建议顺序：note_folder / tag / attachment → note → note_revision
-- → note_tag / note_revision_tag → 回填 note.published_revision_id 外键

create table note (
    id                    varchar(36)  not null primary key,
    kind                  varchar(16)  not null,
    title                 varchar(200) not null,
    content               text         not null,
    summary               varchar(500),
    owner_id              varchar(36)  not null references sys_user (id),
    folder_id             varchar(36)  references note_folder (id),
    pinned                smallint     not null default 0,
    slug                  varchar(200),
    cover_attachment_id   varchar(36)  references attachment (id),
    publish_status        varchar(16),
    published_revision_id varchar(36),
    first_published_at    timestamptz,
    last_published_at     timestamptz,
    draft_modified_at     timestamptz,
    version               bigint       not null default 0,
    create_time           timestamptz  not null,
    update_time           timestamptz,
    delete_time           timestamptz,
    create_by             varchar(36),
    update_by             varchar(36),
    delete_by             varchar(36),
    delete_flag           smallint     not null default 0,
    constraint ck_note_kind check (kind in ('NOTE', 'POST')),
    constraint ck_note_publish_status check (
        publish_status is null
        or publish_status in ('DRAFT', 'PUBLISHED', 'UNPUBLISHED')
    ),
    constraint ck_note_note_only check (
        kind = 'POST'
        or (
            summary is null
            and slug is null
            and cover_attachment_id is null
            and publish_status is null
            and published_revision_id is null
            and first_published_at is null
            and last_published_at is null
            and draft_modified_at is null
        )
    ),
    constraint ck_note_post_only check (
        kind = 'NOTE'
        or (
            folder_id is null
            and publish_status is not null
        )
    ),
    constraint ck_note_draft check (
        publish_status is distinct from 'DRAFT'
        or (
            published_revision_id is null
            and first_published_at is null
            and last_published_at is null
        )
    ),
    constraint ck_note_published check (
        publish_status is distinct from 'PUBLISHED'
        or (
            published_revision_id is not null
            and slug is not null
            and summary is not null
            and first_published_at is not null
            and last_published_at is not null
        )
    ),
    constraint ck_note_unpublished check (
        publish_status is distinct from 'UNPUBLISHED'
        or (
            published_revision_id is not null
            and slug is not null
            and summary is not null
            and first_published_at is not null
            and last_published_at is not null
        )
    ),
    constraint ck_note_slug_format check (
        slug is null or slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    )
);

create table note_revision (
    id                  varchar(36)  not null primary key,
    note_id             varchar(36)  not null references note (id),
    revision_no         int          not null,
    title               varchar(200) not null,
    content             text         not null,
    summary             varchar(500) not null,
    cover_attachment_id varchar(36)  references attachment (id),
    create_by           varchar(36),
    create_time         timestamptz  not null,
    constraint uk_note_revision_no unique (note_id, revision_no)
);

alter table note
    add constraint fk_note_published_revision
        foreign key (published_revision_id) references note_revision (id);

create unique index uk_note_slug
    on note (lower(slug))
    where kind = 'POST' and slug is not null;

create index idx_note_note_list
    on note (owner_id, update_time desc)
    where delete_flag = 0 and kind = 'NOTE';

create index idx_note_post_admin
    on note (publish_status, update_time desc)
    where delete_flag = 0 and kind = 'POST';

create index idx_note_post_public
    on note (pinned desc, first_published_at desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';

create index idx_note_revision_note
    on note_revision (note_id, revision_no desc);
```

补充：

- `uk_note_slug` **不带 `delete_flag = 0`**：slug 是公开 URL 契约，删除后的 slug 也不能被复用，避免旧链接指向新文章。这是对“唯一索引带软删除条件”约定的明确例外。
- `published_revision_id` 外键只能保证 revision 存在，不能保证属于同一篇文章；同一性由 `PublishService` 在事务内保证。
- `note_revision_tag` / `note_tag` 为关联表，物理删除；`note_revision` 为不可变事实表，仅保留 `create_by` / `create_time`，不套审计七件套。相关例外已同步到 `backend/AGENTS.md`。
- 中文搜索 v1 使用 `ILIKE`；数据量上来后再加 `pg_trgm` / GIN 索引，不引入 `zhparser` 等部署依赖。

### 3.8 表例外汇总

| 表 | 例外 |
| --- | --- |
| `note_revision` | 不可变发布事实，只 INSERT；无 `update_time` / 软删除 |
| `note_tag` | 全量替换式关联表，物理删除；仅 `create_by` / `create_time` |
| `note_revision_tag` | 不可变快照关联，无审计字段 |
| `note` slug 唯一索引 | 永久唯一，不带 `delete_flag = 0` |
| `attachment` | 软删除，但删除前需检查封面 / 快照引用 |

---

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

---

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
- v1 不提供公开草稿预览 / 预览 token；后台编辑器内的预览使用当前草稿，并明确提示“线上仍是已发布版本”。
- 公开列表不返回正文；详情返回快照 Markdown。
- 公开列表 / 搜索 / 归档 / 详情上下篇统一支持排序参数 `pinnedFirst` / `direction` / `sortBy`，规则见 §6.4。
- 公开搜索只搜索已发布 revision 的标题 / 正文；不得命中草稿内容。
- RSS / sitemap（如实现）只包含已发布文章。
- 公开响应不得包含 `owner_id`、内部状态、审计字段、附件 storage key 等内部信息；作者只暴露昵称 / 展示名。

---

## 6. API 契约草案

> 正式契约以 OpenAPI 为准；以下只定语义、资源边界和权限。

### 6.1 admin：随手记

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/admin/notes` | `admin:note` | 列表；参数 `keyword` / `folderId` / `tagId` / `page` / `size`；默认按置顶、更新时间降序 |
| `POST /api/admin/notes` | `admin:note:create` | 新建笔记，强制 `kind=NOTE` |
| `GET /api/admin/notes/{id}` | `admin:note` | 详情；只允许本人 / 超管 |
| `PUT /api/admin/notes/{id}` | `admin:note:update` | 更新标题 / 正文 / 文件夹 / 标签 / 置顶；请求带 `version`；不接受 `kind` |
| `DELETE /api/admin/notes/{id}` | `admin:note:delete` | 软删除 |
| `POST /api/admin/notes/{id}/promote` | `admin:post:create` | 转为博客草稿；原笔记不再出现在笔记列表 |
| `GET /api/admin/notes/trash` | `admin:note:delete` | 笔记回收站列表 |
| `POST /api/admin/notes/{id}/restore` | `admin:note:delete` | 恢复笔记 |
| `DELETE /api/admin/notes/{id}/purge` | `admin:note:delete` | 永久删除（P1，可先不实现） |

笔记列表响应建议：`id`、`title`、`folder`、`tags`、`pinned`、`updatedAt`。不在列表返回完整正文。

### 6.2 admin：博客文章

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/admin/posts` | `admin:post` | 列表；参数 `keyword` / `status` / `hasUnpublishedChanges` / `page` / `size` |
| `POST /api/admin/posts` | `admin:post:create` | 直接新建文章草稿（`kind=POST`、`publish_status=DRAFT`） |
| `GET /api/admin/posts/{id}` | `admin:post` | 后台详情：当前草稿 + 已发布快照摘要 + `hasUnpublishedChanges` + 公开 URL |
| `PUT /api/admin/posts/{id}` | `admin:post:update` | 只更新草稿；不接受 `kind` / `publish_status` / `published_revision_id`；请求带 `version` |
| `POST /api/admin/posts/{id}/publish` | `admin:post:publish` | 首次发布 / 更新发布；请求带 `version`；服务端生成或校验 slug |
| `POST /api/admin/posts/{id}/unpublish` | `admin:post:unpublish` | 下架 |
| `DELETE /api/admin/posts/{id}` | `admin:post:delete` | 软删除；已发布先下架 |
| `GET /api/admin/posts/trash` | `admin:post:delete` | 文章回收站列表 |
| `POST /api/admin/posts/{id}/restore` | `admin:post:delete` | 恢复为 `UNPUBLISHED` / `DRAFT` |
| `DELETE /api/admin/posts/{id}/purge` | `admin:post:delete` | 永久删除（P1） |
| `GET /api/admin/posts/{id}/revisions` | `admin:post` | 版本历史（P1） |
| `POST /api/admin/posts/{id}/revisions/{revisionId}/restore` | `admin:post:update` | 回滚为草稿（P1） |

不提供 `POST /api/admin/posts/{id}/demote` 或任何 `POST → NOTE` 接口。

`POST` / `PUT` 请求新增可选字段 `authoredAt`（ISO-8601 字符串）：`null` 表示不修改，空串表示清除；列表 / 详情响应返回 `authoredAt`。它不是内容变更，不更新 `draft_modified_at`，也不要求「更新发布」。

### 6.3 admin：标签管理

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/admin/tags/options` | `admin:note` 或 `admin:post` | 编辑器联想：全部启用标签 id / name |
| `GET /api/admin/tags` | `admin:tag` | 管理列表；含随手记 / 博客草稿 / 已发布快照三层使用量 |
| `POST /api/admin/tags` | `admin:tag:create` | 手动创建标签 |
| `PUT /api/admin/tags/{id}` | `admin:tag:update` | 改名；重名 409；影响所有当前 / 快照展示 |
| `DELETE /api/admin/tags/{id}?force=true` | `admin:tag:delete` | 软删除；有使用量且未 force 时 409 `TAG_IN_USE` |
| `GET /api/admin/tags/{id}/contents?kind=NOTE\|POST` | `admin:tag` | 关联内容；标记 `draftTagged` / `publishedTagged` |
| `GET /api/admin/tags/{id}/candidates?kind=NOTE\|POST` | `admin:tag:assign` | 可添加内容候选（当前草稿未使用该标签） |
| `POST /api/admin/tags/{id}/contents` | `admin:tag:assign` | 给一批内容当前草稿加标签 |
| `DELETE /api/admin/tags/{id}/contents/{contentId}` | `admin:tag:assign` | 从内容当前草稿移除标签 |

给已发布文章加 / 删标签只写当前草稿；线上需在文章编辑页「更新发布」后才变化。

### 6.4 公开博客

| 方法 / 路径 | 说明 |
| --- | --- |
| `GET /api/blog/posts` | 已发布文章列表；参数 `page` / `size` / `tagId` / `year` / `month` / `pinnedFirst` / `direction` / `sortBy`；`year` / `month` 跟随 `sortBy` 字段；返回摘要，不返回正文 |
| `GET /api/blog/posts/{slug}` | 已发布文章详情；正文来自发布快照；额外参数 `pinnedFirst` / `direction` / `sortBy` / `tagId` / `year` / `month` / `q` 用于计算 `prevPost` / `nextPost` |
| `GET /api/blog/tags` | 标签及已发布文章数量 |
| `GET /api/blog/archives` | 全量年月索引；参数 `sortBy` / `direction`，按所选字段统计与排序；月内文章走 `/api/blog/posts?year=&month=` |
| `GET /api/blog/search?q=` | 搜索已发布快照的标题 / 正文；额外支持 `pinnedFirst` / `direction` / `sortBy` |
| `GET /api/blog/feed.xml` | RSS（P1） |
| `GET /api/blog/sitemap.xml` | 站点地图（P1） |
| `GET /api/media/{id}` | 稳定媒体端点；本地 / DB 直接返回，OSS 重定向到 `/oss/{key}`（DIRECT 下 OSS 正文通常直接使用 `/oss/{key}`） |

公开接口匿名可访问，不要求 `entry:blog`；`entry:blog` 只用于未来评论 / 登录互动。`feed.xml` / `sitemap.xml` 为非 JSON 出参，不纳入 TypeScript 生成客户端，需要时由 blog 前端直接请求。

**排序参数（v1.4）**：

| 参数 | 值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `pinnedFirst` | `true` / `false` | `true` | 是否先按置顶分组；`true` 时置顶永远在前，与 `direction` 无关 |
| `direction` | `asc` / `desc` | `desc` | 只作用于时间字段，不翻转置顶 |
| `sortBy` | `displayAt` / `firstPublishedAt` / `lastPublishedAt` | `displayAt` | 时间字段白名单；未来新增字段时追加值 |

- 不限制参数组合：`pinnedFirst=true + direction=asc` 表示“置顶组在前，组内按时间正序”，按文档语义执行即可。
- 值不做组合白名单，但**字段值必须校验**：未知 `sortBy` / `direction` 返回 400，不能把字符串直接拼进查询。
- `sortBy=displayAt` 使用 `coalesce(authored_at, first_published_at)`；对未设置 `authored_at` 的文章与 `firstPublishedAt` 等价。
- 归档采用**按月查看 + 月内分页**：`/api/blog/archives` 只出年月索引；未带 `year` / `month` 时不请求文章列表（页面 `state=empty`，提示选择月份）；带 `year` / `month` 时由 `/api/blog/posts` 取该月文章，`page` / `size` 在月内分页。`year` / `month` 过滤跟随 `sortBy` 字段，不是固定 `firstPublishedAt`。
- `direction` 同时决定年月索引顺序与月内文章顺序；左侧计数与月内 `total` 同源同字段。
- 详情页的 `prevPost` / `nextPost` 按同一组参数计算；若当前文章不在筛选集合内（如手动改 `tagId` / `q`），忽略筛选并按所选排序计算相邻项。
- 搜索页的上下篇携带 `q`，使用与 `/api/blog/search` 相同的标题 / 正文匹配条件，保证停留在搜索结果内。

### 6.5 DTO 与错误

- 请求 DTO 分开：`NoteCreateRequest` / `NoteUpdateRequest` / `PostCreateRequest` / `PostUpdateRequest` / `PublishRequest`。
- `kind`、`publish_status`、`published_revision_id` 不出现在普通更新请求中。
- 更新 / 发布请求都带 `version`；冲突返回 409。
- 列表 DTO 不含正文；公开列表不含任何内部字段。
- 公开摘要 / 详情新增 `lastPublishedAt`、`authoredAt`（可空）、`displayAt`（非空）；`displayAt` / `lastPublishedAt` 列入 `@Schema(requiredProperties)`，`authoredAt` 不列入（生成端按可空处理）；后台 DTO 同步返回 `authoredAt`。
- 建议错误码：

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

---

## 7. 权限与菜单

沿用 `docs/auth-rbac.md`：权限码 = 后端注解、前端按钮 / 页面注册表、`sys_menu` 授权数据的唯一纽带。

### 7.1 权限码

| 权限码 | 类型 | 说明 |
| --- | --- | --- |
| `admin:note` | MENU | 随手记页面 + 读接口 |
| `admin:note:create` | ACTION | 新建笔记 |
| `admin:note:update` | ACTION | 编辑笔记 / 文件夹 |
| `admin:note:delete` | ACTION | 删除 / 回收站 / 恢复 |
| `admin:post` | MENU | 博客管理页面 + 读接口 |
| `admin:post:create` | ACTION | 新建文章 / 笔记转博客 |
| `admin:post:update` | ACTION | 编辑草稿 |
| `admin:post:publish` | ACTION | 首次发布 / 更新发布 |
| `admin:post:unpublish` | ACTION | 下架 |
| `admin:post:delete` | ACTION | 删除 / 回收站 / 恢复 |
| `admin:tag` | MENU | 标签管理页面 + 读接口 |
| `admin:tag:create` | ACTION | 创建标签 |
| `admin:tag:update` | ACTION | 改名标签 |
| `admin:tag:delete` | ACTION | 删除标签（有使用量需 force） |
| `admin:tag:assign` | ACTION | 给内容分配 / 移除标签 |
| `admin:media` | MENU | 媒体库页面 + 读接口 |
| `admin:media:upload` | ACTION | 上传 |
| `admin:media:delete` | ACTION | 删除 |
| `admin:media:storage` | ACTION | 切换新上传存储介质 |

说明：

- `promote` 使用 `admin:post:create`：结果是一篇博客草稿，而不是“修改笔记类型”。
- 记事本没有发布权限码；`admin:post:publish` 只出现在文章接口。
- 公开博客不需要权限码；未来评论使用 `blog:comment:*`。
- v1 单作者；`admin:post` 持有者可管理全部文章，`admin:note` 默认只管理自己的笔记，超管不受限。多作者 / 数据范围后续再设计。
- `admin:media` 的读权限由菜单码承担；上传 / 删除 / 切换存储介质用 ACTION。
- 编辑器联想 `GET /api/admin/tags/options` 允许 `admin:note` 或 `admin:post` 任一访问；标签管理页与写操作使用 `admin:tag:*`。
- 标签改名 / 删除是全局分类操作；删除有使用量时前端二次确认并带 `force=true`。

### 7.2 菜单迁移

在 `sys_menu` 中新增（`V3__add_content_permissions.sql`，id 用 `gen_random_uuid()`，父节点按 code 子查询）：

| code | type | name | parent | sort |
| --- | --- | --- | --- | --- |
| `admin:note` | MENU | 随手记 | `entry:admin` | 40 |
| `admin:note:create` | ACTION | 新建笔记 | `admin:note` | 1 |
| `admin:note:update` | ACTION | 编辑笔记 | `admin:note` | 2 |
| `admin:note:delete` | ACTION | 删除笔记 | `admin:note` | 3 |
| `admin:post` | MENU | 博客管理 | `entry:admin` | 50 |
| `admin:post:create` | ACTION | 新建文章 | `admin:post` | 1 |
| `admin:post:update` | ACTION | 编辑草稿 | `admin:post` | 2 |
| `admin:post:publish` | ACTION | 发布 / 更新发布 | `admin:post` | 3 |
| `admin:post:unpublish` | ACTION | 下架 | `admin:post` | 4 |
| `admin:post:delete` | ACTION | 删除文章 | `admin:post` | 5 |
| `admin:tag` | MENU | 标签管理 | `entry:admin` | 55 |
| `admin:tag:create` | ACTION | 创建标签 | `admin:tag` | 1 |
| `admin:tag:update` | ACTION | 改名标签 | `admin:tag` | 2 |
| `admin:tag:delete` | ACTION | 删除标签 | `admin:tag` | 3 |
| `admin:tag:assign` | ACTION | 分配标签 | `admin:tag` | 4 |
| `admin:media` | MENU | 媒体库 | `entry:admin` | 60 |
| `admin:media:upload` | ACTION | 上传媒体 | `admin:media` | 1 |
| `admin:media:delete` | ACTION | 删除媒体 | `admin:media` | 2 |
| `admin:media:storage` | ACTION | 切换存储介质 | `admin:media` | 3 |

- `Perms` 增加对应常量；启动对账会检查常量、注解和 `sys_menu` 行一致。
- 页面注册表新增 `admin:note` → `/notes`、`admin:post` → `/posts`、`admin:tag` → `/tags`、`admin:media` → `/media`。
- 不新增 `admin:content` 目录；v1 直接作为一级菜单，后续内容模块增多再在菜单管理中调整。

---

## 8. 功能清单

### 8.1 v1（P0）

**共享内容能力**

- Markdown 编辑 / 预览 / 自动保存；
- 标题、正文、标签、文件夹、置顶、回收站；
- 列表、搜索（标题 / 正文 / 标签）、筛选、排序、分页；
- 标签联想复用、标签管理（改名 / 删除 / 使用量 / 内容分配）；
- 图片上传、封面、正文插图、媒体库；
- 软删除 / 恢复、乐观锁、审计字段。

**随手记**

- 默认新建为笔记；
- 文件夹、标签、置顶；
- 私有，不进入任何公开接口；
- 回收站 / 恢复；
- 一键转为博客草稿（不可逆）。

**博客创作**

- 从笔记转入或直接新建；
- 草稿编辑、预览、发布、更新发布、下架；
- 发布面板：slug、摘要、封面；
- 发布快照 / 线上版本指针；
- 文章列表状态：草稿 / 已发布 / 有未发布更改 / 已下架；
- 删除 / 回收站 / 恢复。

**博客前台**

- 首页文章列表、置顶、分页；
- 文章详情、目录、阅读时长、代码复制；
- 标签页、归档页、搜索；
- 404、响应式、暗色主题；
- 基础 meta（title / description / OG 图，来自快照摘要 / 封面）。

**权限与运维**

- 新增权限码与菜单；
- 公开接口严格按 `kind + publish_status + 快照 + 软删除` 过滤；
- 上传校验、附件删除保护、软删除 / 恢复；
- 发布 / 下架的 `update_by` / `update_time` 留痕（独立发布审计表 P1）。

### 8.2 P1

- 评论（独立表、审核、反垃圾、登录 / 匿名策略）；
- 版本历史 UI、回滚、revision 清理策略；
- 定时发布；
- 标签合并 / 别名 / 更细的治理；
- 导入 / 导出 Markdown / ZIP；
- RSS / sitemap / OG / JSON-LD；
- slug 修改与 301 历史；
- 媒体库孤儿清理、附件引用检查；
- `pg_trgm` / 全文搜索索引；
- 桌面端同步（独立设计）。

### 8.3 P2

- 多作者 / 投稿 / 审核流；
- 订阅 / newsletter；
- 阅读量 / 点赞 / 分享统计；
- i18n；
- 笔记分享链接；
- AI 摘要 / 标签建议。

---

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
| `/trash` | 回收站（笔记 / 文章分 Tab） | `admin:note:delete` / `admin:post:delete` |

交互要点：

- 笔记编辑器提供「转为博客」按钮（`v-perm="'admin:post:create'"`），确认后跳转到文章编辑器；**没有反向按钮**。
- 文章编辑器顶部状态徽标：`草稿` / `已发布` / `已发布 · 有未发布更改` / `已下架`。
- 发布按钮按状态变化：「发布」「更新发布」「重新发布」「已发布」。
- 文章发布后 slug 只读；预览明确标注“当前草稿预览，线上仍是已发布版本”。
- 已发布文章删除时二次确认，提示会先下架。
- 列表默认按更新时间降序；公开首页按置顶、首次发布时间降序。

### 9.2 blog 前台

| 路由 | 页面 |
| --- | --- |
| `/` | 首页文章流 / 置顶 |
| `/posts/:slug` | 文章详情 |
| `/tags/:id` | 标签文章列表 |
| `/archives` | 年月索引；`?year=&month=` 为月内分页视图 |
| `/search` | 搜索 |
| `/about` | 关于页（v1 静态内容可接受） |

- 匿名可读；不要求登录。
- **页面形态（v1.3 起）**：后端组装壳（站点配置 + 主题 HTML/CSS）+ Vite 构建的 runtime（打包时进入后端 jar，同源 `/blog/**`），主题由管理端以不可变版本下发；详见 `docs/blog-runtime-theme.md`。
- Markdown 由 blog runtime 使用 **Lute** 渲染（`@simple-notes/markdown` + `method.min.js`），与 admin 共用同一份 `vditorMarkdown`；DOMPurify 在渲染前清洗；方言 v2 允许数学 / emoji / 安全行内 HTML。
- 响应式使用 `useBreakpoint()` / 统一断点值，不按 UA 判断。

---

## 10. Markdown、媒体与安全

- 笔记与文章使用同一套 Markdown 方言（`docs/markdown-dialect.md`），不为博客单独扩展语法。
- 元数据全部走数据库字段，不使用 front matter。
- 后端只存原文，不渲染；公开渲染端在 `innerHTML` 前用 `sanitizeHtml`（DOMPurify）清洗；方言 v2 允许安全行内 HTML。
- 入库清洗策略（2026-10-25 收口）：**不做后端入库清洗**，清洗发生在所有前端渲染入口；若未来需要严格拒绝裸 HTML，再按 Markdown-aware 方案重新评估。
- 正文图片使用可访问 URL（DIRECT：OSS `/oss/{key}`、本地 / DB `/api/media/{id}`），不写 R2 endpoint / bucket；封面使用 `cover_attachment_id`。
- 上传校验：单文件 20 MB、魔数检测、禁止 SVG；非图片文件强制下载；文件名不参与存储路径。任意文件策略见 `docs/attachment-storage.md` §8。
- 公开 bucket 下草稿 / 附件可能被知道 URL 的人访问；个人项目接受，后续可改私有 bucket / 签名 URL。

---

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

---

## 12. 迁移与实现落点

### 12.1 Flyway

- **实现状态**：V2–V10 已落地；后续结构变更走新迁移。
- `V1__init_auth_rbac.sql` 已冻结，不改。
- `V2__init_content.sql`：`note_folder`、`tag`、`attachment`、`note`、`note_revision`、`note_tag`、`note_revision_tag`、索引、约束、`published_revision_id` 外键。
- `V3__add_content_permissions.sql`：内容域 `sys_menu` 节点；id 用 `gen_random_uuid()`，父节点按 code 子查询，不手填 UUID。
- `V4__add_tag_permissions.sql`：标签管理 `admin:tag` 菜单与动作节点。
- `V5__drop_note_status.sql`：移除笔记归档字段 `note.status`，重建不依赖它的 `ck_note_note_only` / `ck_note_post_only` 约束。
- `V10__add_note_authored_at.sql`（v1.4，已执行）：新增 `note.authored_at`、`kind=NOTE` 时为空约束、公开 `displayAt` 表达式索引（`coalesce(authored_at, first_published_at)`）；`last_published_at` 排序索引按需追加。
- 建表顺序：`note_folder` / `tag` / `attachment` → `note` → `note_revision` → 关联表 → `alter table note add constraint fk_note_published_revision`。

### 12.2 后端

- 实体：`Note`、`NoteRevision`、`NoteFolder`、`Tag`、`NoteTag`、`NoteRevisionTag`、`Attachment`；枚举 `NoteKind`、`PublishStatus`。
- `Note` 增加 `@Version` 的 `version` 字段；BaseEntity 不变。
- Repository：派生查询 + QueryDSL；笔记 / 文章查询必须显式带 `kind`。
- Service：
  - `NoteService`：笔记 CRUD、文件夹、回收站、promote；
  - `PostService`：文章草稿 CRUD、详情、状态；
  - `PublishService`：发布、更新发布、下架、revision 创建、slug 校验；
  - `TagService`：标签解析 / 查找 / 快照；
  - `TagAdminService`：标签管理列表 / 改名 / 删除 / 使用量 / 内容分配；
  - `AttachmentService`：上传、删除保护、可访问 URL（DIRECT）/ 稳定端点兜底。
- Controller：`AdminNoteController`、`AdminPostController`、`AdminTrashController`、`AdminMediaController`、`BlogPostController`、`BlogTaxonomyController`、`MediaController`。
- 公开 DTO 与后台 DTO 分开；公开详情从 revision 组装。
- 排序：`BlogSortField`（`displayAt` / `firstPublishedAt` / `lastPublishedAt`）+ `pinnedFirst` / `direction`；`BlogService` 统一生成 `OrderSpecifier`，列表、搜索、归档、详情相邻项共用；`year` / `month` 过滤也使用同一时间字段表达式。
- 相邻项：按当前文章在排序中的位置取严格前一条 / 后一条（`limit 1`），排序尾部用 `note.id` 兜底；不再拉 100 条列表由前端推导；搜索上下文复用 `containsIgnoreCase(title/content)` 条件。
- 回收站查询需要绕过实体级 `@SQLRestriction("delete_flag = 0")`：用专用 Repository + 原生 SQL / `JdbcTemplate`（例外已记录到 `backend/AGENTS.md`）。桌面同步未来的墓碑读取同理。
- 新增 `Perms` 常量并保证启动对账通过。

### 12.3 前端

- `packages/api`：新增端点封装；`pnpm gen:api` 生成类型；禁止手写接口类型。
- admin：
  - 页面注册表新增 `admin:note` / `admin:post` / `admin:tag` / `admin:media`；
  - 笔记列表 / 编辑器、文章列表 / 编辑器、标签管理、媒体库、回收站；
  - `v-perm` 控制按钮；文章状态徽标和发布按钮；
  - Markdown 配置复用共享模块。
- blog：
  - 首页、详情、标签、归档、搜索、关于；
  - 复用 `MdPreview` 共享配置；
  - 只调用公开接口。

### 12.4 测试重点

1. 笔记 CRUD 与 `kind=NOTE` 隔离；
2. `promote` 后原笔记不再出现在笔记列表，文章为 `DRAFT`；
3. 首次发布生成 revision / slug / 时间；
4. 编辑已发布文章后公开内容不变，`hasUnpublishedChanges=true`；
5. 「更新发布」后公开内容变化，`first_published_at` 不变；
6. 下架后公开 404；重新发布无更改复用快照；
7. 删除已发布文章自动下架；恢复为 `UNPUBLISHED`；
8. 不存在 `POST → NOTE` 接口，普通更新不能改 `kind`；
9. 公开接口不泄露笔记 / 草稿 / 已下架内容；
10. slug 唯一、格式、首次发布后不可改、删除后不复用；
11. 标签快照：编辑标签不更新发布，更新发布后才生效；
12. 标签管理：联想选项、使用量、改名影响公开快照、分配 / 移除草稿、force 删除；
12. 权限码与菜单启动对账；
13. `version` 冲突返回 409；
14. 回收站查询能读到软删除行，普通查询读不到；
15. `authoredAt` 为空时公开展示回落 `firstPublishedAt`，设置后覆盖展示、默认排序与归档；笔记写入 `authoredAt` 被拒；
16. `pinnedFirst` / `direction` / `sortBy` 组合：首页 / 标签 / 归档 / 搜索排序，以及详情上下篇方向与筛选上下文；
17. 搜索上下篇携带 `q`，仍停留在搜索结果内；
18. 归档月视图：月份索引计数、`year` / `month` 过滤与月内 `total` 使用同一 `sortBy` 字段；月内分页不截断、跨页计数一致；
19. 未知 `sortBy` / `direction` 返回 400。

---

## 13. 开放问题

1. **评论**：独立表；匿名 / 登录、审核、反垃圾、通知策略待单独设计。
2. **桌面端同步**：增量游标、墓碑读取、冲突解决、附件上传队列——Web 稳定后单独立项。
3. **HTML 清洗**（2026-10-25 已收口）：v2 允许安全行内 HTML；后端不解析 / 不清洗，前端统一 DOMPurify；可信作者模型。
4. **revision 保留策略**：v1 全量保留；何时清理、是否提供回滚 UI，P1 决定。
5. **标签治理**：改名 / 删除 / 使用量 / 内容分配已落地；合并、别名、删除后同名复用策略待后续评估。
6. **slug 变更**：v1 首发布后不可改；未来如需改名，补 `post_slug_history` 与 301。
7. **SEO / SSR**：v1 接受 SPA；若以后需要搜索流量或社交卡片稳定抓取，另做预渲染 / SSR 设计。
8. **多作者 / 数据范围**：v1 单作者；`owner_id` 已预留，后续再定文章协作与笔记隔离规则。
9. **定时发布 / 分类 / 系列**：P1 之后按需要引入。
10. **媒体隐私**：公开 bucket 下的草稿图片隔离是否足够，后续可改私有 bucket / 签名 URL。

---

## 14. 变更记录

| 日期 | 版本 | 变更 |
| --- | --- | --- |
| 2026-10-23 | v1 草案 | 初稿：单表 `note` + `kind`；发布快照 `note_revision`；不提供文章转笔记；评论 / 桌面端 / SEO 延后；权限码、API、功能清单落定 |
| 2026-10-23 | v1 实现 | Web 端首版落地：V2/V3 迁移、`Note` / `NoteRevision` / 标签 / 附件实体、发布快照 API、admin 随手记 / 博客 / 媒体 / 回收站、blog 前台；媒体先接本地存储适配器；评论 / 桌面端 / SSR 保持非目标 |
| 2026-10-23 | v1.1 标签管理 | V4 权限；标签管理页（改名 / 删除 / 三层使用量 / 内容分配）；编辑器全局标签联想；已发布文章分配标签只改草稿，需更新发布；删除有使用量时 force 二次确认 |
| 2026-10-23 | v1.2 移除归档 | 随手记定位云端记事本，修改内容是常态；归档此前仅是状态标签，既不影响列表 / 搜索，也未形成独立入口，收纳需求由软删除 + 回收站覆盖。删除 `note.status` / `NoteStatus` / 相关 DTO 字段与前端开关（V5 迁移），文章下架继续用 `publish_status` |
| 2026-10-24 | v1.3 博客运行时与主题化 | 前台改为「后端组装骨架 + 无构建 runtime + 数据化主题」（`docs/blog-runtime-theme.md`）；Markdown 渲染由 `MdPreview` 改为 runtime 内置 `markdown-it`（方言一致性由 fixture 双向验证）；新增站点配置、独立页面、主题版本与预览能力；内容模型与发布快照不变 |
| 2026-10-25 | v1.3.1 方案 A | blog runtime 改为独立 Vite 构建产物（部署在同域静态路径 / CDN），后端只组装壳；新增 `GET /api/blog/bootstrap`；Markdown 方言与 hljs 复用 `packages/shared`；默认主题收敛到 `packages/theme-starter`；内容模型与发布快照不变 |
| 2026-10-25 | v1.3.2 方案 C | 收敛为单产物：Maven 打包时把 blog dist 拷入 jar 的 `static/blog/`，后端同源提供 `/blog/**`；runtime 只保留壳注入单启动路径；`GET /api/blog/bootstrap` 降级为调试 / agent 接口；内容模型与发布快照不变 |
| 2026-10-28 | v1.4 创作时间与排序上下文 | 新增 `note.authored_at`（运营字段，不参与快照）；公开日期统一为 `displayAt = authoredAt ?? firstPublishedAt`；公开列表 / 搜索 / 归档 / 详情上下篇支持 `pinnedFirst` / `direction` / `sortBy`（字段白名单、不限制组合）；归档改为「月份索引 + 月内分页」，`year` / `month` 跟随 `sortBy`；搜索上下篇携带 `q`；`V10` 迁移已执行 |
| 2026-10-31 | v1.4.1 附件存储设计 | 引用 `docs/attachment-storage.md`：任意文件、20 MB、行级 `storage_mode`、DIRECT 模式（OSS `/oss/{key}` 经媒体域 Nginx 访问 R2，公开 / 私有 bucket 两种；本地 / DB `/api/media/{id}`）；稳定端点保留兜底；内容模型与现有 API 不变 |
| 2026-10-31 | v1.4.2 附件存储实现 | `V11` / `V12`；`admin:media:storage`；媒体库任意文件、封面仅图片、编辑器按 MIME 插入图片 / 链接；私有 R2 + Nginx `auth_request` 方案 B |
