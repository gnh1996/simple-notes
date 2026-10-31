-- =============================================================================
-- 桌面端同步权限（docs/desktop-sync.md §5）
--
-- 只追加：在 entry:desktop 下增加两条 ACTION，桌面会话按内容域分别授权。
-- 权限码与 backend Perms 常量、DesktopContentController 注解保持一致，
-- 由启动对账（MenuCatalogValidator）兜底。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'entry:desktop'),
       'DESKTOP', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values
    ('desktop:note', '同步随手记', 1),
    ('desktop:post', '同步博客文章', 2)
) as v(code, name, sort);
