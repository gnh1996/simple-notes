# 认证与权限设计（RBAC）

> **状态：v1.1 · 2026-10-18**（v1.1 = 设计评审修订：三端统一 Bearer / 超管角色化 / 入口即时回收等，见 §13）
> **实现进度（2026-10-18）**：**后端已落地**（§10 后端落点全部完成 + 集成测试；实现方式与两处细节澄清见 §13）；admin / blog / desktop 三端待实现
> 范围：认证机制（登录 / 会话 / 注销）、RBAC 权限模型（权限目录 / 角色 / 授权）、三端（admin / blog / desktop）准入与落地要点、数据模型、接口草案
> 定稿同步（已完成）：`docs/tech-stack.md`（待定清单 #1 收口 + 变更记录）、根 `AGENTS.md`（文档索引 + 跨端约定）；v1.1 已再次同步 `docs/tech-stack.md`（待定 #3 注记 + 变更记录）
> 关联：`docs/tech-stack.md`（待定：#3 部署形态）、各端 README

## 1. 目标与总览

- 一句话模型：**用户 → N 个角色（权限取并集）→ 权限码集合**；三端准入、admin 菜单、按钮 / 接口校验统一由权限码表达
- 超管（应用拥有者）：唯一预置用户（初始迁移插入，持有预置超管角色），三端可登录，**不受任何权限检查**（含未来新增权限，自动放行）；判定唯一依据 = 是否持有超管角色（§4）
- 角色由拥有者自助创建并授权，示例：
  - **访客**：仅 `entry:blog` —— 只能登录 blog 端
  - **联合创作人**：`entry:admin` + 内容相关权限 —— 可登录 admin 端编辑博客
- v1 范围外：数据范围（行级）权限、第三方登录、用户自助注册、登录审计表（见 §12）

```text
用户 ──┬── 角色 A ──┐
       └── 角色 B ──┴──→ 权限码集合（并集）
                          ├─ entry:*              入口：可登录哪些端
                          ├─ admin:<页面>          admin 菜单：页面可见 + 页面读接口
                          └─ admin:<页面>:<操作>   按钮 / 接口：按钮显隐 + 写接口校验
```

## 2. 核心概念

| 概念 | 说明 |
| --- | --- |
| 用户 User | 登录主体：登录名、密码哈希、显示名、状态 |
| 角色 Role | 权限码的命名集合；用户可拥有多个角色，权限取并集；无否定规则 |
| 权限目录 | 权限节点树（端 → 菜单 → 操作；无对应页面的菜单即分组目录），存储于 `sys_menu` 表；代码侧只引用权限码，启动对账保证一致（§3.4） |
| 权限码 | 稳定字符串标识（如 `admin:post:publish`）；后端注解、前端页面注册表 / 按钮、库中授权数据三方共同引用 |
| 端 Client | `ADMIN` / `BLOG` / `DESKTOP`（接口与库中大写；权限码中小写 `admin` / `blog` / `desktop`） |
| 入口权限 | `entry:admin` / `entry:blog` / `entry:desktop`；登录时校验（能登录哪些端） |
| 会话 Session | JWT 凭证 + 服务端会话记录（可撤销）；三端统一 Bearer（§5） |
| 授权快照 Snapshot | 用户有效权限的进程内缓存（全局版本校验 + TTL 兜底）；用户 / 角色 / 菜单写操作主动失效（§5.3） |
| 权限码集合 | 用户全部角色的有效权限码并集；来自授权快照；超管为"全部放行" |

## 3. 权限目录设计

### 3.1 树结构与节点类型

**端根（ENTRY）→ 菜单（MENU）→ 操作（ACTION）**，每端一棵树，每个节点 = `sys_menu` 表中一行：

| 类型 | 语义 | 示例 |
| --- | --- | --- |
| ENTRY | 端根节点 = 入口权限；勾选后可登录该端 | `entry:admin` |
| MENU | 菜单节点：有对应页面 = 页面可见 + 该页读接口（GET）；无对应页面 = 目录（可嵌套的分组） | `admin:user`（页面）、`admin:content`（目录） |
| ACTION | 操作节点 = 按钮显隐 + 对应写接口 | `admin:user:create` |

- **是否有页面由前端页面注册表判定**（`path` 不落库）：注册表命中 = 页面型；未命中 = 目录型；不设独立类型（"没有页面就是目录"）
- 所有节点统一"**勾选即显示**"：勾选即进入菜单，没有已勾选子级时显示为空分组
- ACTION 可直接挂在端根下（无页面的操作场景：桌面端同步、blog 端互动接口等）
- 超管的"全部权限"不是勾选出来的，而是判定短路（§4）

### 3.2 权限码规范

- 形如 `{端}:{模块}[:{资源}][:{动作}]`，全小写、冒号分隔，全局唯一
- 采用正向命名（`create` / `update` / `delete` / `publish` …），不用 `noXxx` 一类否定词
- 码一经发布**不可更改**（码是后端注解、前端页面注册表 / 按钮指令、库中授权数据的共同引用）；废弃 = 停用节点（§3.4），不移除、不改码

### 3.3 节点语义（关键约定）

- **按钮权限码 = 接口权限码**：前端控件显隐与服务端接口校验使用同一个码；前端只负责"看得见"，**后端为唯一终审**
- 读接口（GET）由所在页面的 MENU 码守护；写 / 变更接口由对应 ACTION 码守护
- 菜单节点分两类（按注册表是否有页面判定）：**页面型**（页面 + 读接口）与**目录型**（仅分组、可嵌套）；目录型"勾选即显示"（空分组亦然），自身不产生接口语义
- 只勾 MENU、不勾 ACTION = 该页面只读（用同一机制实现"只读角色"，无需额外配置）
- 不做权限继承推导：勾选父节点不隐式授予子节点（避免隐式语义与"部分勾选"歧义）

### 3.4 目录存储：数据库菜单表 + 启动对账（决策）

**决策**：权限目录以 `sys_menu` 表为存储（每行 = 一个权限节点：端 / 类型 / 权限码 / 名称 / 排序 / 状态）；代码侧只写"引用的权限码"（接口注解、前端页面注册表 / 按钮指令）。

职责分工：

| 变动 | 路径 |
| --- | --- |
| 新增页面 / 按钮（新权限码） | 代码（注解 / 前端）+ 一条 Flyway **INSERT**（节点行，含同父下初始排序） |
| 改名 / 调序 / 启停 | admin 端「菜单管理」页运行时调整，**不出迁移**；直接作用于侧边栏展示（§6.2） |
| 结构性字段（`code` / `client` / `type` / `parent`） | 界面一律不提供修改；由迁移的初始 INSERT 落定，确需调整属例外，走新迁移并经评审 |
| 废弃节点 | **停用**（`status=0`），不删除（授权行引用它、代码可能仍在引用） |

**为什么 `sys_menu` 不设 `path`（前端路由地址）**：菜单与路由采用"服务端菜单列表驱动"（§6.2）——服务端返回已按权限过滤的可见菜单树，前端只用**页面注册表**（权限码 → 路径 + 组件）把菜单项映射到构建产物中的页面。因此 `path` 不需要进库：路径若进 DB 会形成"无对账兜底"的双源（权限码有启动对账，路径没有；漂移的故障形态是白屏 / 404）；且"前端能加载什么页面"始终由构建产物决定，菜单列表只决定"显示与可进入"。`name` / `sort` 直接作用于侧边栏与菜单顺序（改名 / 调序不发版）。

**启动对账（关键补强）**：代码侧维护 `Perms` 权限码常量类（注解统一引用常量），启动时扫描全部常量、逐一核对 `sys_menu` 中存在对应节点；缺失 → **启动失败并列出缺失码**。这把"双源漂移"（表里有码、代码漏写注解，或反之）从静默故障变为显式启动错误。反向（表中有节点、代码未引用）不阻塞启动。对账同时做结构校验（权限码前缀 ↔ `client` 一致、ACTION 不得有子节点、每端 ENTRY 唯一且为根），一并 fail-fast。

评估记录（为什么不是"纯代码目录"）：备选是目录完全定义在代码、角色只存码——单一来源、零对账，但无法运行时调序 / 改名，目录数据也不便 SQL 查询。选择"菜单表 + 对账"的理由：迁移 INSERT 即权限清单的审计记录（append 型与"脚本只追加"纪律天然兼容）、菜单管理可在 admin 端自助完成、结构性字段受迁移管控。代价是双源 + 启动对账机制（已列入 §10 实现落点）。

### 3.5 授权交互（管理端角色编辑器）

- 目录树从 `sys_menu` 读取（`GET /api/admin/menus`，按端分 Tab 渲染）
- 节点均可勾选（ENTRY / MENU / ACTION），独立勾选、无级联继承；提供"整枝全选 / 清空"快捷操作；父节点半选态 = 部分子节点已选
- 授权存储：`sys_role_permission (role_id, menu_id)` 复合主键、FK 引用；保存 = 集合全量替换（事务内 delete + insert）
- 停用节点：不参与勾选；已有授权运行时视同未授予（过滤 `status=0`）
- 不提供节点删除与新增（创建只走迁移，见 §3.4）

### 3.6 初始权限目录（v1 · 系统域）

| 端 | 码 | 类型 | 说明 |
| --- | --- | --- | --- |
| 通用 | `entry:admin` / `entry:blog` / `entry:desktop` | ENTRY | 三端入口 |
| admin | `admin:user` | MENU | 用户管理（页面 + 读接口） |
| admin | `admin:user:create` / `admin:user:update` / `admin:user:assign-role` / `admin:user:reset-password` / `admin:user:delete` | ACTION | 用户操作（assign-role = 分配角色，独立于 update） |
| admin | `admin:role` | MENU | 角色管理（页面 + 读接口） |
| admin | `admin:role:create` / `admin:role:update` / `admin:role:grant` / `admin:role:delete` | ACTION | 角色操作（`grant` = 修改角色授权，与普通编辑分开） |
| admin | `admin:menu` | MENU | 菜单管理（权限目录维护） |
| admin | `admin:menu:update` | ACTION | 节点改名 / 调序 / 启停 |

约定：

- 个人中心（改自己密码 / 昵称）属"登录即可"，不设权限码（接口见 §8：`PUT /api/auth/password`、`PUT /api/auth/profile`）
- 给用户分配角色独立为 `admin:user:assign-role`（与"分配角色"按钮同码，从 `admin:user:update` 拆出，见 §6.2 / §8）
- 新增功能的标准开发动作 = **代码引用码 + 一条 INSERT 迁移**（对账兜底）
- 预留命名空间（随对应功能设计补充，本次不枚举）：`admin:post:*`、`admin:note:*`、`admin:comment:*`、`admin:media:*`、`admin:settings:*`、`blog:*`、`desktop:*`

## 4. 预置用户与角色（超管）

- **root 用户**（应用拥有者，唯一预置用户）：
  - 初始化：由**初始迁移直接 INSERT**（固定 UUID）；密码哈希 = 固定初始密码 `admin@123456` 的 bcrypt 值（写死在迁移里）；启动不参与创建（`ROOT_USERNAME` / `ROOT_PASSWORD` 配置**取消**）
  - 提示：初始密码公开写在迁移与本文档中，仅作首启使用——**首次登录后应立即在个人中心改密**（v1 不做强制首登改密，见 §12）
  - 约束：不可删除 / 禁用（用户名 v1 全量不可改）；可修改密码与昵称
- **`SUPER_ADMIN` 角色**：与 root 用户、绑定关系（`sys_user_role` 行）一并由初始迁移预置（固定 UUID）；`builtin=1`
  - **判定唯一依据**：授权短路 = 用户是否持有超管角色（授权快照的 `root` 标记即此判定；取消 `sys_user.builtin` 列，避免双源）
  - 持有超管角色的用户：不受任何权限检查、三端入口全开；管理接口整体设防——不可删除 / 禁用 / 改角色集合 / 重置密码（接口拒绝）
  - 不可编辑、不可删除、不可分配给其他用户（不出现在"可分配角色"接口中）
  - 说明：超管能力不落在权限码集合里，因此权限目录新增节点无需为超管补任何数据（"没有任何权限限制"的落地方式）

## 5. 认证机制（收口 tech-stack 待定 #1）

### 5.1 决策：JWT 凭证 + 服务端会话记录（可撤销）

- 凭证统一用 **JWT**（HS256），claims 最小集：`sub`（用户）/ `jti`（会话）/ `iat` / `exp`；三端同一套签发与校验
- **服务端保留会话记录**（`sys_user_session`，键 = `jti`）；校验 = JWT 验签 **+ 会话存在性** —— 解决 JWT"签出去收不回"的固有问题：登出 / 改密 / 禁用 / 删除 → 撤销会话行 → 后续请求 401
- 会话记录存**数据库而非纯内存**：部署重启不丢会话（桌面端 30 天空闲会话尤其）、可运维查询与留痕、未来多实例天然一致；本项目规模下每请求一次主键查询成本可忽略
- **权限不嵌入 JWT**：角色 / 权限变更**不失效 token**——服务端以用户为单位缓存授权快照、写路径主动失效（§5.3），变更下一次请求即生效；避免"改一次角色配置，相关用户被批量踢下线"
- 评估记录（为什么 JWT 而不是随机串）：会话记录必须存服务端的前提下，两者效果等同（JWT 只是更"标准"的会话句柄）；选 JWT 的收益是格式标准化、可解析调试、多端统一认知；代价是密钥管理（`JWT_SECRET`，32+ 字节随机值，配置提供、缺失启动失败）
- **认证校验组件（2026-10-18 变更决策，补齐选型记录）：采用 Spring Security 标准认证链**——`DbUserDetailsService`（从 `sys_user` 加载；用户名归一化、`status != 1` → 账号禁用）+ `DaoAuthenticationProvider`（bcrypt 比对、账号状态检查、时序攻击缓解）+ `AuthenticationManager`（登录端点调用 `authenticate()`）；失败语义映射：`DisabledException` → `LOGIN_USER_DISABLED`，其余 `AuthenticationException`（含用户名不存在，不泄露存在性）→ `LOGIN_CREDENTIALS_INVALID`
  - 收益：复用框架的密码比对 / 账号状态语义 / 时序攻击缓解（对不存在的用户执行等价比对）/ 认证失败事件；避免自研校验在细节上的持续维护
  - 边界不变：**框架承担"验凭证"（加载用户 / 账号状态 / 密码比对）；会话行 / 撤销 / 端类型 / 入口校验 / 授权快照 / 限流仍为自研**（§5.2 / §5.3）——标准件不提供这些语义，故不迁移
  - 附带效果：定义 `UserDetailsService` 后，Spring Boot 默认内存用户自动配置（`UserDetailsServiceAutoConfiguration`，启动打印随机密码）自动失效——不存在幽灵账号（无需显式排除配置）
  - 已知行为差异（测试覆盖）：禁用账号在密码校验前被拒——"禁用 + 错误密码"由变更前的 `LOGIN_CREDENTIALS_INVALID` 变为 `LOGIN_USER_DISABLED`（标准链 `preAuthenticationChecks` 先于密码比对）

### 5.2 登录流程

1. `POST /api/auth/login`，请求体 `{ username, password, client }`
2. 校验顺序：限流 → `AuthenticationManager.authenticate()`（标准链 §5.1：加载用户（归一化小写）→ 账号状态检查（禁用即拒）→ bcrypt 比对）→ 该端入口（权限码集合含 `entry:{client}`；超管直通）
3. 创建会话：生成 `jti`，落会话行（签发时间 / 绝对上限），签发 JWT —— **三端统一：响应体返回 JWT + 身份信息**（不再使用 Cookie）
   - Web（`ADMIN` / `BLOG`）：JWT 由前端持有（localStorage，按应用独立 key，见 §6.2 / §6.3），后续请求以 `Authorization: Bearer` 携带
   - 桌面（`DESKTOP`）：JWT 存入本地凭证存储（§6.4）
4. 登录失败按语义区分：凭据错误 / 无该端登录权限 / 账号被禁用（个人自用场景取可读性，语义码见 §8）
- 限流：按"用户名 + IP"双维度计数，连续 5 次失败 → 锁定 60 秒（内存级实现，重启即清）
- 登录成功更新 `last_login_time` 并记录日志

### 5.3 会话校验与授权快照缓存（每个请求）

1. 取 JWT（统一 `Authorization: Bearer`）→ **验签 + `exp`**（本地快速失败，明显伪造 / 过期的凭证不查库）
2. 查会话行（`jti`）：存在 && 未撤销 && `sub` 与会话 `user_id` 一致 —— **每请求必查、不缓存**（它是撤销通道，缓存在这里会破坏"即时撤销"）
3. 空闲超时检查（`last_active_time`；间隔超过 5 分钟才回写，避免每请求写库；判为超时 → 顺带撤销该会话行并 401）；绝对上限见下表
4. **会话端一致性**：会话 `client` 必须与请求路径域匹配（如 `/api/admin/**` 要求 `client=ADMIN`），否则拒绝 —— 防止"blog 端会话拿去调 admin 接口"
5. **取授权快照**（用户粒度进程内缓存，避免每请求重算"用户 → 角色 → 权限节点"图）：
   - 快照内容：`userId`、`root`（= 持有超管角色）、有效权限码集合（多角色并集、过滤停用角色与停用节点）
   - 命中（版本一致且未过期）：纯内存查找、零库查询；未命中：查库重算并回填；登录时本就需计算入口权限，顺手预热
   - **失效（关键正确性所在）**：任一用户 / 角色 / 菜单写操作（含用户状态变更、用户删除、用户角色分配、角色增删改、角色授权、菜单节点启停）在事务提交后调用 `AuthzCache.bump()`（全局版本 +1）→ 所有快照在下一次请求即重算，**"即时生效"语义不变**；防御性：重算时用户已不存在或已禁用 → 视同无权限
   - **兜底**：快照带 TTL（默认 10 分钟），防"漏调 bump"导致的长期陈旧；正常路径的失效是即时的，TTL 只是保险丝
   - 实现：Caffeine（Spring Boot BOM 管理，Apache-2.0）；以**单实例部署**为前提（多实例缺共享版本时仅剩 TTL 兜底，见 §12）；快照写入须**先读全局版本再查库**，避免与 bump 竞态写出"新版本号 + 旧内容"
6. **入口校验（即时回收）**：快照有效权限码不含 `entry:{会话 client}` → 撤销该会话并 401（root 直通）—— 覆盖"停用 entry 节点 / 角色被收走 entry"等所有入口失效路径，效果等同"下一次请求即被踢"

| 会话端 | 空闲超时 | 绝对上限（= JWT `exp`） |
| --- | --- | --- |
| Web（admin / blog） | 7 天 | 30 天 |
| 桌面 | 30 天 | 180 天 |

- **即时生效**：授权不来自 token 内嵌 claims，也不依赖快照自然过期；角色 / 权限变更在**下一次请求**即生效（写路径 bump 后重算）；禁用 / 删除 = 撤销会话即时失效（§5.4）；入口失效 = 即时回收（§5.3）；不采用"角色 / 权限变更批量踢线"策略
- JWT `exp` 恒定（绝对上限），滑动仅作用于会话行的空闲计时；到期后需重新登录

### 5.4 注销与撤销

| 触发 | 效果 |
| --- | --- |
| `POST /api/auth/logout` | 撤销当前会话行 |
| `POST /api/auth/logout-all` | 撤销该用户全部会话（所有端、全部设备） |
| 用户改密码（需旧密码） | 撤销该用户**除当前外**的全部会话 |
| 管理员重置他人密码 | 撤销被重置用户的全部会话 |
| 禁用 / 删除用户 | **写路径撤销该用户全部会话行**（"让 JWT 失效" = 撤销会话行；无需每请求判定，下一次请求即 401） |
| 入口失效（停用 `entry:*` 节点 / 角色被收走 entry） | 请求链路入口校验发现后撤销该会话并 401（§5.3） |

- JWT 自身无撤销能力，撤销完全依赖会话行 —— 这也是"服务端必须存"的原因
- **认证不再依赖 Cookie**（v1.1）：三端统一 Bearer，天然无 CSRF 面（原双提交方案取消，登录请求同样无需反 CSRF token）；Web 端退出 = 调 logout 接口 + 清本地凭证

## 6. 三端接入设计

### 6.1 接口域与匿名边界

| 域 | 路径 | 匿名 | 登录要求 |
| --- | --- | --- | --- |
| 认证 | `/api/auth/**` | login 等部分接口 | 视接口 |
| admin | `/api/admin/**` | 否 | 会话端 = ADMIN + 权限码 |
| blog | `/api/blog/**` | 公开读接口匿名可访问 | 写操作要求会话端 = BLOG + 权限码 |
| desktop | `/api/desktop/**` | 否 | 会话端 = DESKTOP + 权限码 |

### 6.2 admin 端（Vue）：服务端菜单列表驱动

- 凭证：登录返回的 JWT 存 localStorage key `sn.auth.admin`，请求统一 `Authorization: Bearer`；401（会话失效）→ 清凭证回登录页
- 登录 / 刷新后 `GET /api/auth/me` 拉取 `{ user, root, entries, codes, roles, menus }`（超管 `root=true`）；`menus` = 服务端已按权限过滤的**可见菜单树**（节点 = MENU，含名称 / 排序 / 层级；纳入规则统一为"**自身有授权或有可见后代**"；超管 `root=true` 例外：返回**全量菜单**，不做过滤；ACTION 不出现在菜单载荷中）
- **路由加载（前端无路由级权限判断）**：
  - 前端维护**页面注册表**（权限码 → 路径 + 组件 + 图标，随构建产物发布；非菜单页标记 `hidden`）
  - 按 `menus` 递归处理：**注册表命中 → 注册路由**（路径 / 组件来自注册表）；**未命中 → 目录**（仅递归子节点，自身不注册路由；空目录照常显示，不做校验与告警）
  - 侧边栏直接渲染 `menus`（名称 / 顺序 / 层级均以 `sys_menu` 为准，改名 / 调序不发版）；**勾选即显示**——已勾选但无子级的分组按空分组展示（不做过滤）；注册表项未出现在 `menus` → 不注册（直达未注册路径 → 404）
  - `hidden` 路由（详情 / 编辑页）：默认随 `me` 成功即注册（页面数据接口由后端终审 403）；如需"进都进不去"，给注册项带权限码、注册时按 `codes` 过滤
  - 初始化顺序：**先取 `me` 注册路由、再放行导航**（F5 直连深链时在守卫中等待该流程完成）
- 路由守卫只做会话层判断：未登录 → 登录页；401（会话失效）→ 清状态回登录页；不再有"无码 → 403 页"这类路由级权限判断（"页面是否注册"即权限结论）
- 按钮 / 操作：`v-perm="'admin:user:create'"` 指令（无码即移除 DOM）；`can(code)`（`root || codes.includes(code)`）供表格行操作等动态场景使用
- 收到 403 `PERMISSION_DENIED` 时刷新 `me` 并重建菜单 / 路由（权限刚被调整的场景，避免陈旧界面）
- 用户管理页「分配角色」为独立按钮（`v-perm="'admin:user:assign-role'"`）：弹窗选项来自 `GET /api/admin/roles/assignable`（服务端已排除内置角色）；新建用户不含角色，分配走独立接口
- 角色编辑器：读取完整菜单树渲染勾选面板（§3.5）；「菜单管理」页：改名 / 调序 / 启停（§3.4）

### 6.3 blog 端（Vue）

- 公开页面匿名可读；**登录可选**，仅要求 `entry:blog`
- v1 登录态能力最小集：查看自身身份 / 退出；登录后互动能力（评论等）随 blog 功能设计，命名空间 `blog:*` 预留
- 凭证独立持有：localStorage key `sn.auth.blog`（与 admin 互不干扰——同一浏览器可同时保持两端会话；部署形态见 tech-stack 待定 #3）；未登录触发受保护交互 → 引导登录

### 6.4 desktop 端（.NET / Avalonia）

- 登录 `client=DESKTOP`，要求 `entry:desktop`；JWT 存**本地 SQLite 专表**（依赖操作系统用户级文件权限保护；访问经 `ITokenStore` 抽象，未来可按平台接入 DPAPI / libsecret / Keychain 而不改结构）
  - 评估记录（为什么不上系统钥匙串）：跨平台钥匙串在 Linux 侧依赖 D-Bus / keyring 守护进程（无桌面会话环境不可用），.NET 生态库与 AOT 兼容性不确定；且钥匙串防的是"静态读盘"（磁盘 / 备份 / 同机其他用户），防不了"同用户态进程"（主要威胁），个人设备上收益边际
  - 硬约束：认证数据（令牌表）**永不参与内容同步 / 导出 / 备份**（随同步协议设计落实）
- **本地优先边界**：
  - 登录（或 token 有效期内）后，本地数据读写离线可用；一切与服务端交互（同步 / 发布）在线校验
  - 离线期间发生的授权变更以"下次在线"为准；401 → 提示重新登录，403 → 提示权限已变化
- 桌面端 UI 是否按权限码裁剪本地功能 → 开放问题（随桌面端功能设计）

## 7. 数据模型（建表草案）

- 遵循 backend 约定：主键 `varchar(36)` UUID 字符串、时间列 `timestamptz`、业务表默认审计七件套 + 软删除
- 表前缀 `sys_`（系统域；避免 `user` / `role` 撞 PG 保留字），随首个迁移定稿
- 唯一索引均带 `where delete_flag = 0` 条件（软删除后允许复用登录名 / 角色码；权限码不复用——`sys_menu` 废弃走停用、行不软删，见 §3.4）

### 7.1 sys_user

| 列 | 约束 / 说明 |
| --- | --- |
| id | `varchar(36)` PK |
| username | `varchar(32)` NOT NULL；小写存储；`[a-z0-9][a-z0-9_-]{2,31}`；唯一（部分索引） |
| password_hash | `varchar(100)` NOT NULL；`{bcrypt}$2a$...`（DelegatingPasswordEncoder 格式，留换算法余地）；root 行由迁移写入固定初始密码的哈希（§4） |
| nickname | `varchar(32)` NOT NULL 显示名 |
| email | `varchar(128)` NULL，可选 |
| status | `smallint` 1 启用 / 0 禁用 |
| last_login_time | `timestamptz` NULL |
| 审计七件套 | create / update / delete 时间 + 人、`delete_flag` |

超管保护（不可删除 / 禁用 / 改角色集合）由"持有超管角色"推导（§4），不设用户级 `builtin` 列。

### 7.2 sys_role

| 列 | 约束 / 说明 |
| --- | --- |
| id | `varchar(36)` PK |
| code | `varchar(64)` 唯一（部分索引）；`[A-Z][A-Z0-9_]*`，如 `SUPER_ADMIN`、`CREATOR` |
| name / description | `varchar(32)` / `varchar(200)` |
| builtin | `smallint` 1 = 系统角色：不可编辑 / 删除 / 分配 |
| sort | `int` 列表排序 |
| status | `smallint` 1 启用 / 0 停用（停用 = 授权不生效） |
| 审计七件套 | |

### 7.3 sys_menu（权限节点目录）

| 列 | 约束 / 说明 |
| --- | --- |
| id | `varchar(36)` PK |
| parent_id | `varchar(36)` NULL（ENTRY 端根为 NULL）；FK 自引用 |
| client | `varchar(16)` NOT NULL：ADMIN / BLOG / DESKTOP |
| type | `varchar(16)` NOT NULL：ENTRY / MENU / ACTION（无页面的 MENU 即目录） |
| code | `varchar(128)` NOT NULL；唯一（部分索引）；§3.2 规范 |
| name | `varchar(64)` NOT NULL 显示名（界面可改） |
| sort | `int` NOT NULL；同父下排序（界面可改） |
| status | `smallint` 1 启用 / 0 停用（界面可改；停用 = 不可勾选 + 授权不生效） |
| 审计七件套 | |

结构约束（ENTRY 为根、parent 存在性、code 唯一）由 FK / 唯一索引 / 启动对账共同保证。

### 7.4 关联表（例外说明：物理删除）

- `sys_user_role`：`(user_id, role_id)` 复合主键 + `create_by` / `create_time`；FK 引用两张主表；分配关系物理删除（软删会与"移除后重新分配"冲突），例外在此说明
- `sys_role_permission`：`(role_id, menu_id)` 复合主键 + `create_by` / `create_time`；FK 引用 `sys_role` / `sys_menu`；全量替换式更新；物理删除

### 7.5 sys_user_session（运行数据，例外）

| 列 | 说明 |
| --- | --- |
| id | `varchar(36)` PK，即 JWT 的 `jti` |
| user_id | FK |
| client | `varchar(16)`：ADMIN / BLOG / DESKTOP |
| last_active_time | 滑动续期（>5 分钟才回写） |
| expire_time | 签发时计算 = 签发 + 绝对上限（与 JWT `exp` 一致） |
| revoked / revoke_time | 撤销标记与时间（保留行以留痕） |
| create_time | 签发时间 |

例外说明：运行数据，不用软删除与完整审计；JWT 明文不落库、不落日志（只存 `jti` 会话行）；过期 / 撤销行由清理任务处理（v1 可暂不实现）。索引：`(user_id)`、`(expire_time)`（清理任务与运维查询用）。

### 7.6 迁移

- `V1__init_auth_rbac.sql`：**建齐六张表与索引 + 初始权限节点 INSERT（§3.6）+ 预置数据**（root 用户 / `SUPER_ADMIN` 角色 / 绑定关系，固定 UUID；root 密码哈希为固定初始密码，见 §4）—— 对账 fail-fast 要求首启前节点已存在，故初始节点必须随 V1 落库
- 之后新增权限节点走独立 INSERT 迁移（如 `V<N>__add_<feature>_permissions.sql`），只插入行、不改已有行（§3.4）
- 实体基类（审计字段 / UUID 生成 / 软删过滤）随数据层设计定稿——RBAC 六表为首批实体，实现前先落定该约定（见 §10）

## 8. 接口契约草案

> 正式契约以 OpenAPI（springdoc 导出）为单一来源；下表仅定语义与权限码映射；统一错误响应封装随 API 契约设计定。

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| POST `/api/auth/login` | 匿名 | `{ username, password, client }`；三端统一：响应体返回 JWT + 身份 |
| POST `/api/auth/logout` | 会话 | 撤销当前会话 |
| POST `/api/auth/logout-all` | 会话 | 撤销全部会话 |
| GET `/api/auth/me` | 会话 | 身份 + root + entries + codes + roles；`menus` 仅 ADMIN 端返回（§6.2 / §6.3） |
| PUT `/api/auth/password` | 会话 | 改自己密码（需旧密码） |
| PUT `/api/auth/profile` | 会话 | 改自己昵称 |
| GET `/api/admin/users` | `admin:user` | 分页列表 |
| POST `/api/admin/users` | `admin:user:create` | 创建（登录名 + 初始密码 + 昵称；角色另行分配） |
| PUT `/api/admin/users/{id}` | `admin:user:update` | 昵称 / 邮箱 / 状态 |
| PUT `/api/admin/users/{id}/roles` | `admin:user:assign-role` | 全量替换角色集合（内置角色拒绝；超管角色持有者拒绝） |
| PUT `/api/admin/users/{id}/password` | `admin:user:reset-password` | 重置密码（超管角色持有者拒绝） |
| DELETE `/api/admin/users/{id}` | `admin:user:delete` | 软删除（超管角色持有者拒绝） |
| GET `/api/admin/roles` | `admin:role` | 列表 |
| GET `/api/admin/roles/assignable` | `admin:user:assign-role` | 可分配角色（排除内置角色，供用户管理页） |
| POST `/api/admin/roles` | `admin:role:create` | 创建（内置角色拒绝操作） |
| PUT `/api/admin/roles/{id}` | `admin:role:update` | 名称 / 描述 / 排序 / 状态 |
| PUT `/api/admin/roles/{id}/permissions` | `admin:role:grant` | 全量替换权限码集合 |
| DELETE `/api/admin/roles/{id}` | `admin:role:delete` | 有用户占用时拒绝（占用仅计未删除用户） |
| GET `/api/admin/menus` | `admin:role` 或 `admin:menu`（任一） | 完整菜单树（角色编辑器 / 菜单管理共用） |
| PUT `/api/admin/menus/{id}` | `admin:menu:update` | 改名 / 调序 / 启停（结构性字段与增删不提供） |

错误语义（语义码命名为暂定，随契约统一）：

| 场景 | HTTP | 语义码 |
| --- | --- | --- |
| 未登录 / 会话失效 | 401 | `AUTH_REQUIRED` |
| 登录凭据错误 | 401 | `LOGIN_CREDENTIALS_INVALID` |
| 账号被禁用 | 403 | `LOGIN_USER_DISABLED` |
| 无该端登录权限 | 403 | `LOGIN_ENTRY_DENIED` |
| 登录限流 | 429 | `LOGIN_RATE_LIMITED` |
| 已登录但无权限 | 403 | `PERMISSION_DENIED` |

## 9. 安全要点

- 密码：BCrypt（Spring Security 内置，无需额外依赖；cost 采用默认）；策略 ≥ 8 位（自用场景不求复杂度）；全链路不落日志；root 初始密码为固定值（迁移内置，首启后应立即改密，§4）
- 认证链：登录凭据校验走 Spring Security 标准链（`DbUserDetailsService` + `DaoAuthenticationProvider`，§5.1）——账号禁用检查先于密码比对、用户名不存在与密码错误同码不泄露存在性（含对不存在用户的等价比对缓解时序探测）；应用无任何默认 / 内置账号（默认内存用户自动配置已被自定义 `UserDetailsService` 顶替，启动不再输出随机密码）
- JWT：HS256，密钥 `simple-notes.auth.jwt-secret: "${JWT_SECRET:}"`（32+ 字节随机；无默认、缺失启动失败）；claims 最小化（不嵌权限 / 角色）；JWT 明文不落库不落日志
- 凭证持有：三端统一 Bearer，不再使用 Cookie（无 CSRF 面，登录请求同样无需反 CSRF token）；Web 端 token 存 localStorage——**XSS 暴露面为已接受代价**（缓解：严格 CSP / 无第三方脚本 / 服务端可撤销 + 会话绝对上限）
- 超管面收窄：`SUPER_ADMIN` 不可分配 / 编辑 / 删除；持有超管角色的用户不可删除 / 禁用 / 改角色集合 / 重置密码；用户删除默认软删
- 授权缓存失效属安全路径：用户 / 角色 / 菜单写操作必须 bump（集中收口、测试覆盖；TTL 兜底）；用户禁用 / 删除另撤销其全部会话（§5.4）
- 启动校验：JWT 密钥缺失、权限码对账缺失（含 §3.4 结构校验）两处 fail-fast（root / 超管角色已由迁移预置，无启动初始化）；会话表过期清理为后台遗留项（§12）
- 已知风险（接受）：拥有 `admin:user:assign-role` 的用户可将任意非内置角色分配给他人 / 自己（提权）——个人自用场景接受，服务端仍强制"内置角色不可分配"

## 10. 实现落点（进入实现时展开）

- **后端**：`spring-boot-starter-security` + `spring-security-oauth2-jose`（JWT 签发 / 校验，Nimbus；坐标随 Spring Security BOM 7.1.1，无需手写版本）+ Caffeine（BOM 管理）；`DbUserDetailsService` + `DaoAuthenticationProvider` / `AuthenticationManager`（登录凭据校验标准链，§5.1）；`SessionJwtFilter`（Bearer 验签 → 查会话 → 入口校验 → 授权快照；撤销路径集中收口）；`AuthzCache`（Caffeine；**先读全局版本再查库**；全局版本 bump + TTL 兜底；菜单树不进快照，`me` 每次实时查询）；路径域规则（会话端一致性）；方法级权限校验（`@PreAuthorize` + `Perms` 常量，超管角色短路）；**启动对账扫描器**（扫描 `Perms` 常量核对 `sys_menu`：缺失 fail-fast + 结构校验（§3.4））；Flyway V1（建表 + 初始节点 + 预置 root / 超管角色 / 绑定，§7.6）
- **admin**：`stores/auth`（me + 菜单树缓存；凭证存 localStorage `sn.auth.admin`，请求带 Bearer，无 CSRF 逻辑）；页面注册表（权限码 → 路径 / 组件）与动态路由注册（按菜单列表 `addRoute`；先菜单后放行的初始化顺序）；`v-perm` 指令；登录页；用户管理（含「分配角色」独立按钮）、角色管理（菜单树勾选组件）、菜单管理（改名 / 调序 / 启停）
- **blog**：登录 / 退出（v1 最小能力集；凭证 `sn.auth.blog`）；**desktop**：登录窗 + 本地凭证存取（`ITokenStore`）+ 401/403 处理
- 三端 API 调用一律走生成客户端（契约定稿后；本轮不涉及）

## 11. 文档联动

| 对象 | 动作 |
| --- | --- |
| `docs/tech-stack.md` | 待定清单 #1 已收口（JWT + 服务端会话记录，存数据库）；变更记录已追加；待定 #3 已补注部署形态影响（v1.1 复核：认证不再依赖 Cookie，注记已改写） |
| 根 `AGENTS.md` | 文档索引已收录本文；「跨端关键约定」已补：权限码为前后端唯一纽带，前端仅显隐、后端终审 |
| 桌面端 token 存储（待定 #1 后半） | 已定：**本地 SQLite + 用户级文件权限**（`ITokenStore` 抽象保留平台加密扩展）；不引入跨平台钥匙串依赖 |

v1.1 评审修订同步：`docs/tech-stack.md`（待定 #3 注记改写 + 变更记录追加）；根 `AGENTS.md` 无需改动。

## 12. 开放问题

1. blog 端登录态的业务能力（评论 / 收藏等）—— 随 blog 功能设计
2. desktop 端本地功能是否按权限裁剪 —— 随桌面端功能设计
3. 在线会话管理 UI（设备列表 / 踢下线）：建议 v2（v1 仅"退出所有设备"）
4. 管理员重置密码后是否强制首登改密：建议 v1 不做
5. 登录审计表 `sys_login_log`：v1 走应用日志
6. 权限码 → 前端 TS 常量生成（防手写偏差）：v1 手写 + 后端对未知码兜底；建议实现时优先解决（注册表 / `v-perm` 拼错码 = 页面 / 按钮静默不显示，用后端导出清单做构建期校验成本很低）
7. 用户名是否允许修改：v1 不允许
8. JWT 密钥轮换流程：v1 单一密钥、不轮换
9. 多实例部署时的授权缓存版本共享（v1 单实例部署，进程内版本足够；多实例需共享版本机制，否则仅剩 TTL 兜底）
10. 超管转让 / 多超管：v1 唯一预置（root 持有超管角色），转让只能人工 SQL；如需多超管再评估

## 13. 变更记录

- **2026-10-18** 初稿（待评审）
- **2026-10-18** 修订：目录存储由"纯代码目录"改为**数据库菜单表**（迁移只 INSERT 节点、改名 / 调序 / 启停走 admin 端、启动对账兜底）；认证凭证改为 **JWT + 服务端会话记录**（会话存数据库；权限不嵌 JWT，不做"权限变更踢线"）
- **2026-10-18** 修订：启动对账严格级别确认 **fail-fast**（缺失即启动失败）；权限计算由"每请求实时重算"改为**授权快照缓存**（用户粒度快照 + 全局版本 bump 即时失效 + 10 分钟 TTL 兜底，单实例前提；会话行查询仍每请求执行、不缓存）
- **2026-10-18** 修订：桌面端令牌存储由"系统钥匙串"简化为**本地 SQLite + 文件权限**（`ITokenStore` 抽象保留平台加密扩展；认证表不参与同步 / 导出 / 备份）
- **2026-10-18** 评审澄清：`sys_menu` **不设 `path`**——路由目标（路径 / 组件 / 图标）唯一来源为前端页面注册表，前后端以权限码关联；菜单可见性判断基于授权码而非路径
- **2026-10-18** 修订：admin 端菜单与路由改为**服务端菜单列表驱动**（`me` 返回已过滤菜单树；前端页面注册表按权限码映射路径与组件、动态注册路由；前端不再做路由级权限判断；`sys_menu` 仍不设 path）
- **2026-10-18** 评审补充：MENU 可为**无界面的容器父级**（菜单树纳入规则 = 自身授权或有可见后代；前端注册表查缺时按"有子节点 = 容器、无子节点 = 告警"分流；侧边栏过滤空容器）
- **2026-10-18** 评审补充：取消 DIR 特殊处理——**所有节点统一"勾选即显示"**（空分组照常展示，不做过滤）；菜单纳入规则统一为"自身授权或有可见后代"；仅保留"叶子 MENU 无注册项 → 开发期告警"一条例外
- **2026-10-18** 评审补充：**取消 DIR 类型与叶子 MENU 告警例外**——统一为 MENU，"没有页面就是目录"（判定在前端页面注册表）；前端不再对菜单做校验与告警，仅"注册表命中则注册路由、未命中按目录递归"
- **2026-10-18** v1 定稿（评审通过）：同步 `docs/tech-stack.md`（待定 #1 收口 + 变更记录）与根 `AGENTS.md`（文档索引 + 跨端约定）
- **2026-10-18** **v1.1 评审修订**：
  - 会话回收：禁用 / 删除用户统一为**写路径撤销该用户全部会话行**（原"每请求实时判定"取消；"让 JWT 失效"= 撤销会话行）
  - 超管载体收口：root 用户 + `SUPER_ADMIN` 角色 + 绑定关系**由初始迁移预置**（固定 UUID；root 初始密码固定 `admin@123456`，首登应改密）；短路**唯一依据 = 持有超管角色**（取消 `sys_user.builtin` 列与 `ROOT_USERNAME` / `ROOT_PASSWORD` 配置）；管理接口整体设防（不可删除 / 禁用 / 改角色集合 / 重置密码）
  - 入口失效**即时回收**：请求链路增加 `entry:{会话 client}` 校验，缺失即撤销会话并 401（覆盖停用入口节点与角色被收走 entry 两种路径）
  - **取消 Cookie 与 CSRF 方案**，三端统一 Bearer（登录响应体统一返回 JWT；Web 凭证 localStorage 独立 key）——原"同域共享 cookie"导致的会话互斥问题随之消解
  - 角色分配独立为 `admin:user:assign-role`（新增 `PUT /api/admin/users/{id}/roles`、`GET /api/admin/roles/assignable`；创建用户不含角色）；角色分配提权记录为**已知风险并接受**
  - 新增 `PUT /api/auth/profile`；`me` 契约明确 `menus` 仅 ADMIN 端返回（超管返回全量菜单，不做过滤）
  - 实现约定：快照先读版本再查库、过滤停用角色与停用节点；菜单树不进快照；对账加结构校验；角色删除占用判断 join 活跃用户；V1 = 建表 + 初始节点 + 预置数据；限流用户名 + IP 双维度；`expire_time` 索引 + 空闲驳回顺带撤销会话
- **2026-10-18** **后端实现落地**（实现与本文档无冲突；实现选择见下）：
  - 完成 §10 后端落点全部：`spring-security-oauth2-jose`（HS256）+ Caffeine；`SessionJwtFilter`（验签 → 会话行 → 空闲 / 绝对超时 → 端一致性 → 快照 → 入口回收）；`AuthzCache`（先读版本再查库 + 事务提交后 bump + 10 分钟 TTL）；路径域规则；方法级 `@PreAuthorize` + `Perms` 常量 + 超管短路；启动对账（Perms 缺失 / 结构校验 / 注解引用的码未声明 → fail-fast）；`V1__init_auth_rbac.sql`（六表 + 初始节点 + 预置 root / `SUPER_ADMIN` / 绑定，root 初始密码 `admin@123456`）
  - 落地接口与 §8 契约一致：`/api/auth/*`（login / logout / logout-all / me / password / profile）+ admin 用户 / 角色 / 菜单管理与 `roles/assignable`；错误体 `{code, message}`，语义码在 §8 之外增补 `VALIDATION_FAILED` / `NOT_FOUND` / `CONFLICT` / `METHOD_NOT_ALLOWED` / `INTERNAL_ERROR`
  - 实现选择 ①：接口注解的权限码写成字符串字面量（SpEL 内引用 Java 常量需 `T(全限定名)`，可读性差），启动对账**额外扫描 `@PreAuthorize`** 校验其已在 `Perms` 声明——原子写偏差同样 fail-fast，与"注解统一引用常量"目标等价
  - 实现选择 ②：无效 / 已撤销凭证按**匿名**处理（受保护接口仍 401；公开接口不受陈旧凭证影响），跨端凭证（blog 会话调 admin 接口）在本域视同无效——"拒绝"语义通过与匿名一致的 401 实现
  - 实现选择 ③：仓储层零手写 JPQL（派生查询 + QueryDSL；关联表用 `@IdClass` 扁平主键；集合全量替换 = `deleteAll` + `flush` + `saveAll`；会话撤销 / 续期为实体态 + 脏检查）——约定已写入 `backend/AGENTS.md`「查询约定」
  - 验证：Testcontainers（postgres:17-alpine）+ MockMvc 集成测试 34 例（登录语义 / 限流 / 入口即时回收 / 授权变更即时生效 / 会话超时与撤销 / 内置角色与超管保护 / 启动对账 fail-fast）
  - **冻结提醒**：`V1__init_auth_rbac.sql` 已在测试环境执行——此后按"脚本只追加"纪律，任何修正走新迁移
- **2026-10-18** 变更决策：**认证校验由自研改为 Spring Security 标准认证链**（`DbUserDetailsService` + `DaoAuthenticationProvider` + `AuthenticationManager`，登录端点调用 `authenticate()`）——补齐 §5.1"验凭证为何不用框架标准件"的选型记录（原 §5 只论证了 JWT / 会话存储 / 权限不嵌 token，认证校验组件属隐式选择）；边界不变（会话 / 撤销 / 端类型 / 入口 / 快照 / 限流仍自研）；默认内存用户自动配置随自定义 `UserDetailsService` 失效；已知行为差异：禁用账号在密码比对前被拒（§5.1）
