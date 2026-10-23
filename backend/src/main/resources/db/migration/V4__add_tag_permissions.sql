-- =============================================================================
-- 标签管理权限目录（docs/content-model.md §3.3 / §7）
--
-- V3 已执行，本迁移只追加；新节点 id 用 gen_random_uuid()，父节点按 code 子查询。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:tag', '标签管理', 55, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:tag'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:tag:create', '创建标签', 1),
             ('admin:tag:update', '改名标签', 2),
             ('admin:tag:delete', '删除标签', 3),
             ('admin:tag:assign', '分配标签', 4)) as v(code, name, sort);
