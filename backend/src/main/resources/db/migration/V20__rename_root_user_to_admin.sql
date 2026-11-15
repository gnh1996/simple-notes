-- =============================================================================
-- 预置超管登录名 root → admin（首个测试版本前的命名收口）
--
-- 背景：V1 预置的拥有者账号登录名为 root；对外发布前统一为 admin（与「超级管理员」语义一致）。
-- 本迁移只改登录名，不动固定 UUID、密码哈希、SUPER_ADMIN 角色与 sys_user_role 绑定关系。
--
-- 为什么追加而不是改 V1：V1 已在开发库执行（backend/AGENTS.md「数据库迁移约定」），
-- 只能新增更高版本补偿；修改 V1 会触发 Flyway 校验和不匹配。
-- =============================================================================

-- 冲突保护：admin 已被未删除用户占用时直接失败，避免触发唯一索引后留下半截状态
do
$$
begin
    if exists (select 1 from sys_user where username = 'admin' and delete_flag = 0) then
        raise exception '无法将预置超管改名为 admin：已存在未删除的同名用户';
    end if;
end
$$;

update sys_user
set username    = 'admin',
    update_time = now()
where id = '00000000-0000-0000-0000-000000000001'
  and username = 'root'
  and delete_flag = 0;
