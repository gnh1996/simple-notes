-- =============================================================================
-- 桌面端图片上传权限（docs/desktop-media.md）
--
-- 只追加：在 entry:desktop 下增加一条 ACTION；桌面端插图不访问 /api/admin/**。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'entry:desktop'),
       'DESKTOP', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values
    ('desktop:media:upload', '上传图片', 10)
) as v(code, name, sort);
