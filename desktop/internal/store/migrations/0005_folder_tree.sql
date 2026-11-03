-- 文件夹树（v2.0）：本地文件夹增加 parent_id，唯一性改为「scope + parent + name」。
-- 同时移除 v1.4/v1.5 的内置「本地文件」：历史笔记统一落到「未分类」。
alter table folder_local add column parent_id text;

drop index if exists uk_folder_local_scope_name;

create unique index if not exists uk_folder_local_scope_parent_name
    on folder_local (scope_id, coalesce(parent_id, ''), lower(name))
    where scope_id = 'local';

update note_local
   set folder_id = ''
 where scope_id = 'local'
   and folder_id = 'local-files';
