-- 创作时间：搬运 / 导入文章的原始日期，仅 POST 使用。
-- 运营 / 展示字段：不参与发布快照，修改立即影响公开博客的展示、排序与归档。
alter table note add column authored_at timestamptz;

comment on column note.authored_at is '仅 POST：创作时间 / 原始日期；NULL 时公开博客回落 first_published_at';

alter table note add constraint ck_note_authored_at_note
    check (kind = 'POST' or authored_at is null);

-- 公开默认排序：置顶 + displayAt（authored_at 优先，回落 first_published_at）。
create index idx_note_post_public_display on note (pinned desc, (coalesce(authored_at, first_published_at)) desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';

-- 归档默认 pinnedFirst=false：纯 displayAt 时间线。
create index idx_note_post_public_display_time on note ((coalesce(authored_at, first_published_at)) desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';

-- 按最近发布时间排序（可选 sortBy=lastPublishedAt）。
create index idx_note_post_public_last on note (pinned desc, last_published_at desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';

-- 纯首次发布时间排序（归档 pinnedFirst=false + sortBy=firstPublishedAt）。
create index idx_note_post_public_first_time on note (first_published_at desc)
    where delete_flag = 0 and kind = 'POST' and publish_status = 'PUBLISHED';
