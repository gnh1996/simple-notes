-- =============================================================================
-- 站点设置 / 主题管理权限目录（docs/blog-runtime-theme.md §9.1）
--
-- V6–V8 已执行，本迁移只追加；新节点 id 用 gen_random_uuid()，父节点按 code 子查询。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:site', '站点设置', 70, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:site'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:site:update', '修改站点设置', 1)) as v(code, name, sort);

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:theme', '主题管理', 75, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:theme'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:theme:write', '编辑主题', 1),
             ('admin:theme:activate', '应用 / 回滚主题', 2)) as v(code, name, sort);
