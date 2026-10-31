-- =============================================================================
-- 桌面端编辑权限（docs/desktop-sync.md §5，M1）
--
-- 只追加：在 entry:desktop 下增加两条写权限，读 / 写分离，便于只读角色。
-- 权限码与 Perms 常量、DesktopContentController 注解保持一致。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'entry:desktop'),
       'DESKTOP', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values
    ('desktop:note:update', '编辑随手记', 3),
    ('desktop:post:update', '编辑博客草稿', 4)
) as v(code, name, sort);
