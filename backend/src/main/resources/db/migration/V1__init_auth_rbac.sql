-- =============================================================================
-- RBAC 初始化迁移：六表 + 初始权限目录 + 预置超管（root / SUPER_ADMIN / 绑定）
-- 设计依据：docs/auth-rbac.md §3.6（初始权限目录）、§4（预置超管）、§7（数据模型）、§7.6（迁移口径）
--
-- 约定（与 backend/AGENTS.md 一致）：
--   * 主键 = varchar(36) 应用层生成的 UUID 字符串；时间列 timestamptz
--   * 业务表（sys_user / sys_role / sys_menu）带审计七件套；查询默认排除 delete_flag = 1
--   * 关联表（sys_user_role / sys_role_permission）为物理删除例外（§7.4）：仅 create_by / create_time
--   * sys_user_session 为运行数据例外（§7.5）：无软删与完整审计，撤销保留行以留痕
--   * 唯一索引带 delete_flag = 0 条件：软删后允许复用登录名 / 角色码；
--     权限码不复用（sys_menu 行只停用不软删，§3.4），故其唯一索引不带条件
-- =============================================================================

-- -----------------------------------------------------------------------------
-- sys_user：用户
-- -----------------------------------------------------------------------------
create table sys_user
(
    id              varchar(36)  not null primary key,
    username        varchar(32)  not null,
    password_hash   varchar(100) not null,
    nickname        varchar(32)  not null,
    email           varchar(128),
    status          smallint     not null default 1,
    last_login_time timestamptz,
    create_time     timestamptz  not null,
    update_time     timestamptz,
    delete_time     timestamptz,
    create_by       varchar(36),
    update_by       varchar(36),
    delete_by       varchar(36),
    delete_flag     smallint     not null default 0
);

comment on table sys_user is '用户（登录主体，软删除）';
comment on column sys_user.username is '登录名：小写存储，形如 [a-z0-9][a-z0-9_-]{2,31}';
comment on column sys_user.password_hash is '密码哈希：{bcrypt}$2a$...（DelegatingPasswordEncoder 格式）';
comment on column sys_user.status is '1 = 启用；0 = 禁用';

create unique index uk_sys_user_username on sys_user (username) where delete_flag = 0;

-- -----------------------------------------------------------------------------
-- sys_role：角色
-- -----------------------------------------------------------------------------
create table sys_role
(
    id          varchar(36) not null primary key,
    code        varchar(64) not null,
    name        varchar(32) not null,
    description varchar(200),
    builtin     smallint    not null default 0,
    sort        int         not null default 0,
    status      smallint    not null default 1,
    create_time timestamptz not null,
    update_time timestamptz,
    delete_time timestamptz,
    create_by   varchar(36),
    update_by   varchar(36),
    delete_by   varchar(36),
    delete_flag smallint    not null default 0
);

comment on table sys_role is '角色：权限码的命名集合（用户权限 = 多角色并集）';
comment on column sys_role.code is '角色码：[A-Z][A-Z0-9_]*，唯一，创建后稳定';
comment on column sys_role.builtin is '1 = 内置角色：不可编辑 / 删除 / 分配（如 SUPER_ADMIN）';
comment on column sys_role.status is '1 = 启用；0 = 停用（停用 = 授权不生效）';

create unique index uk_sys_role_code on sys_role (code) where delete_flag = 0;

-- -----------------------------------------------------------------------------
-- sys_menu：权限目录（每行 = 一个权限节点：ENTRY / MENU / ACTION）
-- -----------------------------------------------------------------------------
create table sys_menu
(
    id          varchar(36)  not null primary key,
    parent_id   varchar(36),
    client      varchar(16)  not null,
    type        varchar(16)  not null,
    code        varchar(128) not null,
    name        varchar(64)  not null,
    sort        int          not null default 0,
    status      smallint     not null default 1,
    create_time timestamptz  not null,
    update_time timestamptz,
    delete_time timestamptz,
    create_by   varchar(36),
    update_by   varchar(36),
    delete_by   varchar(36),
    delete_flag smallint     not null default 0,
    constraint fk_sys_menu_parent foreign key (parent_id) references sys_menu (id)
);

comment on table sys_menu is '权限目录：端（ENTRY）→ 菜单（MENU）→ 操作（ACTION）；代码侧经启动对账保证一致';
comment on column sys_menu.client is '端：ADMIN / BLOG / DESKTOP（与权限码前缀一致）';
comment on column sys_menu.type is 'ENTRY = 端根；MENU = 菜单 / 目录（有无页面由前端注册表判定）；ACTION = 操作';
comment on column sys_menu.code is '权限码：{端}:{模块}[:{资源}][:{动作}]，全局唯一且不复用（废弃 = 停用）';
comment on column sys_menu.status is '1 = 启用；0 = 停用（不可勾选 + 授权运行时视同未授予）';

-- 权限码行不软删（废弃走停用），唯一约束直接作用于 code 全量
create unique index uk_sys_menu_code on sys_menu (code);
create index idx_sys_menu_parent on sys_menu (parent_id);

-- -----------------------------------------------------------------------------
-- sys_user_role：用户 → 角色（全量替换式更新；物理删除例外）
-- -----------------------------------------------------------------------------
create table sys_user_role
(
    user_id     varchar(36) not null,
    role_id     varchar(36) not null,
    create_by   varchar(36),
    create_time timestamptz not null,
    primary key (user_id, role_id),
    constraint fk_sys_user_role_user foreign key (user_id) references sys_user (id),
    constraint fk_sys_user_role_role foreign key (role_id) references sys_role (id)
);

comment on table sys_user_role is '用户-角色关联（物理删除例外，§7.4）';

create index idx_sys_user_role_role on sys_user_role (role_id);

-- -----------------------------------------------------------------------------
-- sys_role_permission：角色 → 权限节点（全量替换式更新；物理删除例外）
-- -----------------------------------------------------------------------------
create table sys_role_permission
(
    role_id     varchar(36) not null,
    menu_id     varchar(36) not null,
    create_by   varchar(36),
    create_time timestamptz not null,
    primary key (role_id, menu_id),
    constraint fk_sys_role_permission_role foreign key (role_id) references sys_role (id),
    constraint fk_sys_role_permission_menu foreign key (menu_id) references sys_menu (id)
);

comment on table sys_role_permission is '角色-权限节点授权（物理删除例外，§7.4）';

create index idx_sys_role_permission_menu on sys_role_permission (menu_id);

-- -----------------------------------------------------------------------------
-- sys_user_session：会话（JWT 的 jti 为此表主键；运行数据例外，§7.5）
-- -----------------------------------------------------------------------------
create table sys_user_session
(
    id               varchar(36) not null primary key,
    user_id          varchar(36) not null,
    client           varchar(16) not null,
    last_active_time timestamptz not null,
    expire_time      timestamptz not null,
    revoked          boolean     not null default false,
    revoke_time      timestamptz,
    create_time      timestamptz not null,
    constraint fk_sys_user_session_user foreign key (user_id) references sys_user (id)
);

comment on table sys_user_session is '登录会话：id = JWT jti；撤销 / 过期保留行以留痕；JWT 明文不落库';
comment on column sys_user_session.client is '会话端：ADMIN / BLOG / DESKTOP（须与请求路径域一致）';
comment on column sys_user_session.last_active_time is '最近活跃时间（滑动续期；间隔超过 5 分钟才回写）';
comment on column sys_user_session.expire_time is '绝对上限 = 签发时间 + 该端上限（与 JWT exp 一致）';

create index idx_sys_user_session_user on sys_user_session (user_id);
create index idx_sys_user_session_expire on sys_user_session (expire_time);

-- =============================================================================
-- 初始权限目录（§3.6）：通用入口 + admin 系统域
-- 固定 UUID：...0001/0002 预置数据；...001x 入口；...002x 用户；...003x 角色；...004x 菜单
-- =============================================================================

-- 端根（ENTRY）
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values ('00000000-0000-0000-0000-000000000010', null, 'ADMIN', 'ENTRY', 'entry:admin', '管理端', 0, 1, now()),
       ('00000000-0000-0000-0000-000000000011', null, 'BLOG', 'ENTRY', 'entry:blog', '博客端', 0, 1, now()),
       ('00000000-0000-0000-0000-000000000012', null, 'DESKTOP', 'ENTRY', 'entry:desktop', '桌面端', 0, 1, now());

-- admin：用户管理
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000010', 'ADMIN', 'MENU',
        'admin:user', '用户管理', 10, 1, now()),
       ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000020', 'ADMIN', 'ACTION',
        'admin:user:create', '创建用户', 1, 1, now()),
       ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000020', 'ADMIN', 'ACTION',
        'admin:user:update', '编辑用户', 2, 1, now()),
       ('00000000-0000-0000-0000-000000000023', '00000000-0000-0000-0000-000000000020', 'ADMIN', 'ACTION',
        'admin:user:assign-role', '分配角色', 3, 1, now()),
       ('00000000-0000-0000-0000-000000000024', '00000000-0000-0000-0000-000000000020', 'ADMIN', 'ACTION',
        'admin:user:reset-password', '重置密码', 4, 1, now()),
       ('00000000-0000-0000-0000-000000000025', '00000000-0000-0000-0000-000000000020', 'ADMIN', 'ACTION',
        'admin:user:delete', '删除用户', 5, 1, now());

-- admin：角色管理
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values ('00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000010', 'ADMIN', 'MENU',
        'admin:role', '角色管理', 20, 1, now()),
       ('00000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000030', 'ADMIN', 'ACTION',
        'admin:role:create', '创建角色', 1, 1, now()),
       ('00000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000030', 'ADMIN', 'ACTION',
        'admin:role:update', '编辑角色', 2, 1, now()),
       ('00000000-0000-0000-0000-000000000033', '00000000-0000-0000-0000-000000000030', 'ADMIN', 'ACTION',
        'admin:role:grant', '修改授权', 3, 1, now()),
       ('00000000-0000-0000-0000-000000000034', '00000000-0000-0000-0000-000000000030', 'ADMIN', 'ACTION',
        'admin:role:delete', '删除角色', 4, 1, now());

-- admin：菜单管理
insert into sys_menu (id, parent_id, client, type, code, name, sort, status, create_time)
values ('00000000-0000-0000-0000-000000000040', '00000000-0000-0000-0000-000000000010', 'ADMIN', 'MENU',
        'admin:menu', '菜单管理', 30, 1, now()),
       ('00000000-0000-0000-0000-000000000041', '00000000-0000-0000-0000-000000000040', 'ADMIN', 'ACTION',
        'admin:menu:update', '编辑菜单', 1, 1, now());

-- =============================================================================
-- 预置超管（§4）：root 用户 + SUPER_ADMIN 角色 + 绑定关系
--   * 初始密码：admin@123456（固定值，仅作首启使用，首次登录后应立即在个人中心改密）
--   * 超管判定唯一依据 = 是否持有 SUPER_ADMIN 角色（授权短路，不受权限检查）
-- =============================================================================
insert into sys_user (id, username, password_hash, nickname, status, create_time)
values ('00000000-0000-0000-0000-000000000001', 'root',
        '{bcrypt}$2a$10$87zV0cKP9d8NrFuhmEd/x.WuAZlU11WW4k/qWqEMha9B8JwBcn/bW',
        '管理员', 1, now());

insert into sys_role (id, code, name, description, builtin, sort, status, create_time)
values ('00000000-0000-0000-0000-000000000002', 'SUPER_ADMIN', '超级管理员',
        '应用拥有者（预置内置角色：不受任何权限检查，不可编辑 / 删除 / 分配）', 1, 0, 1, now());

insert into sys_user_role (user_id, role_id, create_time)
values ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', now());
