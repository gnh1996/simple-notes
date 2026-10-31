-- =============================================================================
-- 媒体库存储设置权限（docs/attachment-storage.md §7.5）
--
-- 只追加：在既有 admin:media 菜单下增加切换存储介质的 ACTION。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:media'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:media:storage', '切换存储介质', 3)) as v(code, name, sort);
