-- =============================================================================
-- 主题删除权限（docs/blog-runtime-theme.md §9.1）
--
-- 只允许删除非线上主题；线上主题需先切换或回退内置默认主题。
-- 新节点 id 用 gen_random_uuid()，父节点按 code 子查询。
-- =============================================================================

insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
select gen_random_uuid(), (select id from sys_menu where code = 'admin:theme'),
       'ADMIN', 'ACTION', 'admin:theme:delete', '删除主题', 3, 1, now();
