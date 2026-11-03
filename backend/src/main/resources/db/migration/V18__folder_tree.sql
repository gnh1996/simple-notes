-- 文件夹树（v2.0）：新增 parent_id，唯一性从「owner + name」改为「owner + parent + name」。
-- 历史文件夹 parent_id 为 NULL（根），同级仍唯一；不同层级允许同名。
alter table note_folder add column parent_id varchar(36);

drop index if exists uk_note_folder_owner_name;

create unique index uk_note_folder_owner_parent_name
    on note_folder (owner_id, coalesce(parent_id, ''), lower(name))
    where delete_flag = 0;

create index idx_note_folder_owner_parent
    on note_folder (owner_id, parent_id)
    where delete_flag = 0;

comment on column note_folder.parent_id is '父文件夹 id；根为 NULL（最多 5 级）';
comment on table note_folder is '记事本文件夹（v2.0 树形；删除文件夹不删除笔记）';
