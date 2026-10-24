-- =============================================================================
-- 主题存储（docs/blog-runtime-theme.md §6.1）
--
-- 约定：
--   * site_theme 为身份 + 当前生效版本指针，带审计七件套与软删除
--   * site_theme_version 为不可变主题版本：只 INSERT，无 update_time / 软删除
--   * format 标识主题包形态：1 = html + css（后续形态新增编号，存量行不动）
-- =============================================================================

create table site_theme
(
    id                varchar(36) not null primary key,
    name              varchar(64) not null,
    active_version_id varchar(36),
    create_time       timestamptz not null,
    update_time       timestamptz,
    delete_time       timestamptz,
    create_by         varchar(36),
    update_by         varchar(36),
    delete_by         varchar(36),
    delete_flag       smallint    not null default 0
);

comment on table site_theme is '博客主题身份；生效版本指针指向 site_theme_version';

create unique index uk_site_theme_name on site_theme (lower(name)) where delete_flag = 0;

create table site_theme_version
(
    id          varchar(36) not null primary key,
    theme_id    varchar(36) not null,
    version_no  int         not null,
    format      smallint    not null default 1,
    html        text        not null,
    css         text        not null,
    contract    int         not null,
    create_by   varchar(36),
    create_time timestamptz not null,
    constraint fk_site_theme_version_theme foreign key (theme_id) references site_theme (id),
    constraint uk_site_theme_version_no unique (theme_id, version_no)
);

comment on table site_theme_version is '不可变主题版本；只 INSERT，回滚通过移动 site_theme.active_version_id';

create index idx_site_theme_version_theme on site_theme_version (theme_id, version_no desc);

alter table site_theme
    add constraint fk_site_theme_active_version
        foreign key (active_version_id) references site_theme_version (id);
