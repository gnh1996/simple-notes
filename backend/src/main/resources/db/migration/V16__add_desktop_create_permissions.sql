-- =============================================================================
-- 桌面端离线新建权限（docs/desktop-sync.md §5，M3）
--
-- 只追加：在 entry:desktop 下增加两条 ACTION；新建接受客户端 UUID。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'entry:desktop'),
       'DESKTOP', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values
    ('desktop:note:create', '新建随手记', 8),
    ('desktop:post:create', '新建博客草稿', 9)
) as v(code, name, sort);
