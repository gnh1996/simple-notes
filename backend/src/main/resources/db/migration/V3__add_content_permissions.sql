-- =============================================================================
-- 内容模块权限目录（docs/content-model.md §7）
--
-- 约定（docs/auth-rbac.md §3.4 / backend/AGENTS.md）：
--   * 新节点 id 用 gen_random_uuid()，不手填 UUID
--   * 父节点按 code 子查询；先插入 MENU，再插入 ACTION
--   * 权限码一经发布不可改；废弃走停用
-- =============================================================================

-- admin：随手记
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:note', '随手记', 40, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:note'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:note:create', '新建笔记', 1),
             ('admin:note:update', '编辑笔记', 2),
             ('admin:note:delete', '删除笔记', 3)) as v(code, name, sort);

-- admin：博客管理
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:post', '博客管理', 50, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:post'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:post:create', '新建文章', 1),
             ('admin:post:update', '编辑草稿', 2),
             ('admin:post:publish', '发布 / 更新发布', 3),
             ('admin:post:unpublish', '下架', 4),
             ('admin:post:delete', '删除文章', 5)) as v(code, name, sort);

-- admin：媒体库
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values (gen_random_uuid(), (select id from sys_menu where code = 'entry:admin'),
        'ADMIN', 'MENU', 'admin:media', '媒体库', 60, 1, now());

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:media'),
       'ADMIN', 'ACTION', v.code, v.name, v.sort, 1, now()
from (values ('admin:media:upload', '上传媒体', 1),
             ('admin:media:delete', '删除媒体', 2)) as v(code, name, sort);
