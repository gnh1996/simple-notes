-- -----------------------------------------------------------------------------
-- 移除笔记归档（docs/content-model.md 变更记录 v1.2）
-- 随手记定位云端记事本，修改内容是常态；收纳由软删除 + 回收站覆盖。
-- 文章从未使用 status，下架继续用 publish_status。
-- -----------------------------------------------------------------------------

alter table note drop constraint ck_note_post_only;
alter table note drop constraint ck_note_note_only;
alter table note drop constraint ck_note_status;
alter table note drop column status;

alter table note
    add constraint ck_note_note_only check (
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
    add constraint ck_note_post_only check (
        kind = 'NOTE'
        or (
            folder_id is null
            and publish_status is not null
        )
    );
