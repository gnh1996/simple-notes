-- =============================================================================
-- 内容模块初始化：随手记 / 博客文章共用 note 表 + 发布快照
-- 设计依据：docs/content-model.md §3（数据模型）、§4（发布模型）、§12（迁移）
--
-- 约定（与 backend/AGENTS.md 一致）：
--   * 主键 varchar(36)，应用层生成 UUID；时间列 timestamptz
--   * note / note_folder / tag / attachment 带审计七件套，默认软删除
--   * note_revision 为不可变发布事实：只 INSERT，无 update_time / 软删除
--   * note_tag / note_revision_tag 为关联表：物理删除，不参与软删除
--   * note.slug 唯一索引不带 delete_flag 条件：公开 URL 永久保留（明确例外）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- note_folder：记事本文件夹（v1 单层）
-- -----------------------------------------------------------------------------
create table note_folder
(
    id          varchar(36) not null primary key,
    owner_id    varchar(36) not null,
    name        varchar(64) not null,
    sort        int         not null default 0,
    create_time timestamptz not null,
    update_time timestamptz,
    delete_time timestamptz,
    create_by   varchar(36),
    update_by   varchar(36),
    delete_by   varchar(36),
    delete_flag smallint    not null default 0,
    constraint fk_note_folder_owner foreign key (owner_id) references sys_user (id)
);

comment on table note_folder is '记事本文件夹（v1 单层；删除文件夹不删除笔记）';
create unique index uk_note_folder_owner_name on note_folder (owner_id, lower(name)) where delete_flag = 0;
create index idx_note_folder_owner on note_folder (owner_id) where delete_flag = 0;

-- -----------------------------------------------------------------------------
-- tag：标签（笔记 / 文章共用）
-- -----------------------------------------------------------------------------
create table tag
(
    id          varchar(36) not null primary key,
    name        varchar(32) not null,
    create_time timestamptz not null,
    update_time timestamptz,
    delete_time timestamptz,
    create_by   varchar(36),
    update_by   varchar(36),
    delete_by   varchar(36),
    delete_flag smallint    not null default 0
);

comment on table tag is '标签：笔记与文章共用；公开博客只读发布快照上的标签';
create unique index uk_tag_name on tag (lower(name)) where delete_flag = 0;

-- -----------------------------------------------------------------------------
-- attachment：附件元数据（R2 / 本地存储 key 与稳定站内 URL 解耦）
-- -----------------------------------------------------------------------------
create table attachment
(
    id            varchar(36)  not null primary key,
    owner_id      varchar(36)  not null,
    storage_key   varchar(255) not null,
    original_name varchar(255) not null,
    mime_type     varchar(128) not null,
    size_bytes    bigint       not null,
    width         int,
    height        int,
    alt_text      varchar(255),
    create_time   timestamptz  not null,
    update_time   timestamptz,
    delete_time   timestamptz,
    create_by     varchar(36),
    update_by     varchar(36),
    delete_by     varchar(36),
    delete_flag   smallint     not null default 0,
    constraint fk_attachment_owner foreign key (owner_id) references sys_user (id),
    constraint uk_attachment_storage_key unique (storage_key)
);

comment on table attachment is '附件元数据；正文使用稳定站内 URL，不直接暴露存储 key';
create index idx_attachment_owner on attachment (owner_id) where delete_flag = 0;

-- -----------------------------------------------------------------------------
-- note：随手记 / 博客文章共用主表
-- -----------------------------------------------------------------------------
create table note
(
    id                    varchar(36)  not null primary key,
    kind                  varchar(16)  not null,
    title                 varchar(200) not null,
    content               text         not null,
    summary               varchar(500),
    owner_id              varchar(36)  not null,
    folder_id             varchar(36),
    pinned                smallint     not null default 0,
    status                varchar(16)  not null default 'ACTIVE',
    slug                  varchar(200),
    cover_attachment_id   varchar(36),
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
    constraint fk_note_owner foreign key (owner_id) references sys_user (id),
    constraint fk_note_folder foreign key (folder_id) references note_folder (id),
    constraint fk_note_cover_attachment foreign key (cover_attachment_id) references attachment (id),
    constraint ck_note_kind check (kind in ('NOTE', 'POST')),
    constraint ck_note_status check (status in ('ACTIVE', 'ARCHIVED')),
    constraint ck_note_pinned check (pinned in (0, 1)),
    constraint ck_note_publish_status check (
        publish_status is null
        or publish_status in ('DRAFT', 'PUBLISHED', 'UNPUBLISHED')
    ),
    constraint ck_note_note_only check (
        kind = 'POST'
        or (
            status in ('ACTIVE', 'ARCHIVED')
            and summary is null
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
            status = 'ACTIVE'
            and folder_id is null
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

comment on table note is '内容主表：kind=NOTE 随手记 / kind=POST 博客文章；note 保存当前草稿';
comment on column note.kind is '内容类型：NOTE / POST；创建后不可改（唯一例外 promote）';
comment on column note.publish_status is '文章发布状态：DRAFT / PUBLISHED / UNPUBLISHED；笔记为 NULL';
comment on column note.published_revision_id is '当前公开快照指针；公开博客只读该 revision';
comment on column note.slug is '公开 URL 标识；首次发布后不可修改，删除后不复用';
comment on column note.first_published_at is '首次发布时间；文章日期与列表排序使用';
comment on column note.last_published_at is '最近一次发布时间';
comment on column note.draft_modified_at is '文章草稿最近修改时间；用于 hasUnpublishedChanges';

-- slug 永久唯一：公开 URL 契约，删除后的 slug 不允许复用
create unique index uk_note_slug on note (lower(slug)) where kind = 'POST' and slug is not null;

create index idx_note_note_list on note (owner_id, update_time desc)
    where delete_flag = 0 and kind = 'NOTE';
create index idx_note_post_admin on note (publish_status, update_time desc)
    where delete_flag = 0 and kind = 'POST';
create index idx_note_post_public on note (pinned desc, first_published_at desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';
create index idx_note_folder on note (folder_id) where delete_flag = 0 and kind = 'NOTE';

-- -----------------------------------------------------------------------------
-- note_revision：不可变发布快照（只 INSERT）
-- -----------------------------------------------------------------------------
create table note_revision
(
    id                  varchar(36)  not null primary key,
    note_id             varchar(36)  not null,
    revision_no         int          not null,
    title               varchar(200) not null,
    content             text         not null,
    summary             varchar(500) not null,
    cover_attachment_id varchar(36),
    create_by           varchar(36),
    create_time         timestamptz  not null,
    constraint fk_note_revision_note foreign key (note_id) references note (id),
    constraint fk_note_revision_cover foreign key (cover_attachment_id) references attachment (id),
    constraint uk_note_revision_no unique (note_id, revision_no)
);

comment on table note_revision is '不可变发布快照：每次发布 / 更新发布新增一行；公开博客只读它';
create index idx_note_revision_note on note_revision (note_id, revision_no desc);

alter table note
    add constraint fk_note_published_revision
        foreign key (published_revision_id) references note_revision (id);

-- -----------------------------------------------------------------------------
-- 标签关联：当前草稿标签 / 发布快照标签
-- -----------------------------------------------------------------------------
create table note_tag
(
    note_id     varchar(36) not null,
    tag_id      varchar(36) not null,
    create_by   varchar(36),
    create_time timestamptz not null,
    constraint pk_note_tag primary key (note_id, tag_id),
    constraint fk_note_tag_note foreign key (note_id) references note (id),
    constraint fk_note_tag_tag foreign key (tag_id) references tag (id)
);

comment on table note_tag is '当前草稿标签（全量替换式更新，物理删除）';
create index idx_note_tag_tag on note_tag (tag_id);

create table note_revision_tag
(
    revision_id varchar(36) not null,
    tag_id      varchar(36) not null,
    constraint pk_note_revision_tag primary key (revision_id, tag_id),
    constraint fk_note_revision_tag_revision foreign key (revision_id) references note_revision (id),
    constraint fk_note_revision_tag_tag foreign key (tag_id) references tag (id)
);

comment on table note_revision_tag is '发布快照标签：发布时从 note_tag 复制，不可变';
create index idx_note_revision_tag_tag on note_revision_tag (tag_id);
