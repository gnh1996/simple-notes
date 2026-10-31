-- =============================================================================
-- 桌面端删除 / 文件夹写权限（docs/desktop-sync.md §5，M2）
--
-- 只追加：在 entry:desktop 下增加三条 ACTION，读 / 写分离。
-- 权限码与 Perms 常量、DesktopContentController 注解保持一致。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'entry:desktop'),
       'DESKTOP', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values
    ('desktop:note:delete', '删除随手记', 5),
    ('desktop:post:delete', '删除博客草稿', 6),
    ('desktop:folder:write', '管理文件夹', 7)
) as v(code, name, sort);
