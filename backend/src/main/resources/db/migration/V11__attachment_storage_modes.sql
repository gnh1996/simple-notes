-- =============================================================================
-- 附件存储介质（docs/attachment-storage.md）
--
-- 既有 attachment 行全部由本地适配器写入，统一回填 LOCAL。
-- DATABASE 模式的二进制放独立 attachment_blob，避免 attachment 列表查询触碰大字段。
-- =============================================================================

alter table attachment add column storage_mode varchar(16);

update attachment set storage_mode = 'LOCAL' where storage_mode is null;

alter table attachment alter column storage_mode set not null;
alter table attachment add constraint ck_attachment_storage_mode
    check (storage_mode in ('LOCAL', 'OSS', 'DATABASE'));

create index idx_attachment_storage_mode
    on attachment (storage_mode) where delete_flag = 0;

create table attachment_blob
(
    storage_key varchar(255) not null primary key,
    content     bytea        not null,
    create_time timestamptz  not null,
    constraint fk_attachment_blob_attachment
        foreign key (storage_key) references attachment (storage_key) on delete cascade
);

comment on table attachment_blob is '数据库模式的附件二进制；仅通过 DatabaseAttachmentStorage 访问';
