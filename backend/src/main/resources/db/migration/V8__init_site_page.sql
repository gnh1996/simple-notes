-- =============================================================================
-- 独立页面（docs/blog-runtime-theme.md §6.3）
--
-- 约定：
--   * 保存即线上，不做发布快照（与文章刻意不同）
--   * about 为预置页面，承接原硬编码的“关于”内容
-- =============================================================================

create table site_page
(
    id          varchar(36)  not null primary key,
    slug        varchar(64)  not null,
    title       varchar(200) not null,
    content     text         not null,
    visible     smallint     not null default 1,
    sort        int          not null default 0,
    create_time timestamptz  not null,
    update_time timestamptz,
    delete_time timestamptz,
    create_by   varchar(36),
    update_by   varchar(36),
    delete_by   varchar(36),
    delete_flag smallint     not null default 0
);

comment on table site_page is '独立页面（关于等）；保存即线上，无发布快照';

create unique index uk_site_page_slug on site_page (lower(slug)) where delete_flag = 0;

insert into site_page (id, slug, title, content, visible, sort, create_time)
values (gen_random_uuid(), 'about', '关于',
        'Simple Notes 是一个随手记与个人博客合一的小项目。

平时用来记录零散想法、整理笔记；值得分享的内容会转为博客文章，发布到这里。

技术栈：Spring Boot · PostgreSQL · Vue 3 · Markdown',
        1, 0, now());
