# backend 项目规则

服务端：Spring Boot 4.1.1 / Java 25 / Maven（wrapper）；数据层 JPA + QueryDSL + Flyway + PostgreSQL。

## 数据模型约定

- **业务表主键用 UUID（v4），应用层生成，以字符串存储**，不依赖数据库自增 / 序列：
  - Java 侧 id 用 `String`（标准小写 36 字符格式），生成统一走 `UUID.randomUUID().toString()`
  - 数据库列用 `varchar(36)`（不用 PG 原生 `uuid` 类型）：ID 在各层按不透明字符串对待（存储 / Java / 契约 / 桌面端一致），为潜在换库（如 SQLite）或更换生成方案（雪花 / ULID 等）留余地；此数据量级下原生类型的存储 / 比较优势可忽略
  - JPA 不使用数据库生成策略（`@GeneratedValue`）；接入方式随实体基类设计定稿，注意 id 预先非 null 时 Spring Data 的"新实体判定"（必要时用 `Persistable`）
  - 取向说明：个人数据量级下 UUIDv4 随机插入无实际影响；无 workerId / 时钟回拨负担；桌面端本地优先可离线生成 ID、双端不冲突（曾评估雪花算法，不采用）

- **业务表默认带审计字段**（软删除 + 操作留痕，属默认标配，建表迁移一并带上）：

  | 语义 | 建议列名 | 说明 |
  | --- | --- | --- |
  | 创建时间 | `create_time` | 记录创建时刻 |
  | 更新时间 | `update_time` | 最近一次更新时刻 |
  | 删除时间 | `delete_time` | 未删除为 NULL |
  | 创建人 | `create_by` | 创建者 |
  | 更新人 | `update_by` | 最近一次更新者 |
  | 删除人 | `delete_by` | 执行删除者 |
  | 删除状态 | `delete_flag` | 0 = 未删除；1 = 已删除 |

- 列名以首批实体 / 迁移落地时定稿为准（倾向实体基类 + JPA Auditing 统一维护，随数据层设计确定）
- 删除默认软删除：置 `delete_flag = 1` 并记录删除时间 / 删除人；查询默认排除已删除数据
- 例外表（无需审计或确需物理删除）须在对应设计文档中说明
- 已落地的实体基类：`domain/BaseEntity`（UUID 主键预生成 + `Persistable` 新实体判定 + 审计回调 + 软删辅助）；查询侧排除已删除数据用实体级 `@SQLRestriction("delete_flag = 0")`，不再逐查询手写条件
- **内容模块例外**（设计见 `docs/content-model.md` §3.8）：`note_revision` 为不可变发布快照，仅 `create_by` / `create_time`，无软删除；`note_tag` / `note_revision_tag` 为关联表，物理删除；`note.slug` 唯一索引不带 `delete_flag = 0`（公开 URL 永久保留）
- **站点配置 / 主题例外**（设计见 `docs/blog-runtime-theme.md` §6）：`site_theme_version` 为不可变主题版本，仅 `create_by` / `create_time`，无软删除；`site_setting` 为 KV 配置，物理 upsert（新增即 INSERT、写入即 UPDATE、移除即 DELETE），无 `delete_flag`；`site_page` 保存即线上，不做发布快照。主题版本保存必须走 `ThemeTemplateValidator`（组件 / 指令 / 模板键白名单 + 行号错误），`theme.overrides` 必须走 `SiteSettingCatalog.validateThemeOverrides`（变量白名单 + 安全 CSS 值）
- **附件存储例外**（设计见 `docs/attachment-storage.md`）：`attachment.storage_mode` 按行记录实际介质；`attachment_blob` 是 DATABASE 模式的从属二进制，仅 `storage_key` / `content` / `create_time`，无审计七件套（生命周期完全由 `attachment` 决定）；二进制读写用 `JdbcTemplate` 流式 API，不用 JPA `byte[]` / `@Lob`

## 附件存储（docs/attachment-storage.md）

- **写路径读全局、读路径读行**：`site_setting.storage.mode` 只决定新上传；历史读取 / 删除必须按 `attachment.storage_mode` 路由，禁止用全局模式覆盖
- **R2 私有 bucket + 方案 B**：Nginx `auth_request` 调 `/api/internal/media/presign` 取短期预签名 URL，再代理 R2；内部接口必须校验 `X-Internal-Token`，禁止外部直达
- **任意文件策略**：魔数检测；禁止 SVG；只有图片 inline，其他强制 `Content-Disposition: attachment`；`cover_attachment_id` 只接受图片；文件名不参与存储路径
- **流式 I/O**：上传 / 读取不得 `MultipartFile.getBytes()` 或 JPA 大字段整块物化；DATABASE 单文件上限 20 MB，本地 / R2 走流
- **Nginx 示例**：`deploy/nginx/media.conf.example`；修改缓存 / 限流 / 媒体域策略时同步更新该示例与设计文档

## 博客前台（壳组装，设计见 docs/blog-runtime-theme.md）

- 后端只组装壳：注入 site + 主题 HTML/CSS + CSP / 缓存头；**不得在服务端执行主题模板**（不要引入 Thymeleaf / 服务端模板引擎渲染主题）
- **页面路由归 runtime**：`BlogPageController` 只保留一条 catch-all（`SHELL_PATH`，排除 `/api`、`/blog`、`/v3`、`/error`、`/favicon.svg`）做 history fallback，不登记具体页面；新增前端路由不改后端。未知页面返回 `200 + 壳`（SEO 非目标，见设计文档 §4.5）
- 因此安全规则是"`/api/**` 认证 + 非 API 的 GET 公开"：新增业务控制器**必须**挂在 `/api/` 下，`startup/ApiPathValidator` 启动期校验，漏写前缀直接启动失败
- runtime 由 `frontend/apps/blog` 用 Vite 构建；Maven `prepare-package` 把 `dist` 拷入 jar 的 `static/blog/`，后端同源提供 `/blog/**`。**`mvn test` / `compile` 不依赖 dist，`mvn package` 依赖先执行 `pnpm build:blog`**
- 壳 ETag = 主题版本 + 配置版本 + asset-base + `SHELL_VERSION`；非预览 `s-maxage=60`，预览 `no-store` + `no-referrer`
- `/blog/runtime.js` 用 `no-cache`，`/blog/chunks/**`、`/blog/assets/**` 长缓存 + `immutable`（`BlogAssetCacheFilter`）
- 开发期：`mvn spring-boot:run` 默认启用 `dev` profile（`application-dev.yml`），`asset-base` 自动指向 Vite；生产固定 `/blog`。需要覆盖时仍可用 `BLOG_ASSET_BASE` 环境变量
- `GET /api/blog/bootstrap` 是调试 / agent / 未来桌面端接口，**不参与首屏启动**；返回 DTO 必须补 `@Schema(requiredProperties = ...)`，并跑 `pnpm gen:api`
- 默认主题唯一源在 `frontend/packages/theme-starter`；后端 `blog/default-theme/` 是兜底副本，只由 `pnpm sync:theme` 更新，`pnpm check:theme` 校验

## 认证与权限（RBAC，设计见 docs/auth-rbac.md）

- **权限码是唯一纽带**：新增权限 = `security/Perms` 加常量 + 一条 Flyway `INSERT`（`sys_menu` 节点行，id 用 `gen_random_uuid()`、父节点按 code 子查询，见「数据库迁移约定」）；接口注解 `@PreAuthorize("@authz.can('...')")` 里的码必须与 `Perms` 声明一致——启动对账（`startup/MenuCatalogValidator`）扫描常量与注解，缺失 / 漂移直接启动失败
- 超管短路：持有 `SUPER_ADMIN` 角色的用户不受任何权限检查（含未来新增码），判定与放行都在 `AuthzGuard`
- **授权快照缓存（`AuthzCache`）**：任何用户 / 角色 / 菜单写操作必须在事务提交后调用 `bumpAfterCommit()`（immediate 生效语义靠它）；用户禁用 / 删除 / 重置密码另需经 `SessionService` 撤销其全部会话
- 会话撤销通道 `sys_user_session`（主键 = JWT `jti`）每请求必查、不缓存；撤销路径集中收口在 `SessionService`，不要在业务代码里散写
- 例外表：`sys_user_role` / `sys_role_permission` 物理删除（全量替换式更新，仅 `create_by` / `create_time`）；`sys_user_session` 为运行数据（无软删与完整审计，过期 / 撤销行保留留痕）
- JWT 密钥 `simple-notes.auth.jwt-secret`（环境变量 `JWT_SECRET`）无默认值，缺失 / 不足 32 字节启动失败；JWT 明文与密码不落库、不落日志

## API 契约（OpenAPI，设计见 docs/tech-stack.md）

- **契约单一来源 = 运行中的应用**：springdoc 暴露 `GET /v3/api-docs`（`config/OpenApiConfig` 声明元信息与 Bearer 方案；`SecurityConfig` 对该路径匿名放行）；Web / 桌面端一律据此生成客户端（Web：`frontend/packages/api`，`pnpm gen:api`）
- 改完接口后跑一次 `pnpm gen:api`（`frontend/` 下），生成物 `packages/api/src/generated/schema.d.ts` 随仓库提交；**禁止在客户端手写接口类型**
- 请求 DTO（record + jakarta validation 注解）：必填字段必须有校验注解（springdoc 据此标记 `required`）
- 响应 DTO（record）：类级 `@Schema(requiredProperties = {...})` 列出**恒存在的 key**（Jackson 默认连 null 值一并输出，故可空字段也属于恒存在）；「值可能为 null」的字段（如 `email` / `lastLoginTime` / `description` / `parentId` / `menus`）不列入该清单，生成端 TS 类型保持可选，客户端需自行兜底
- `AuthPrincipal` 等框架注入参数不出现在契约中（springdoc 忽略）；新增非 JSON 出参 / 特殊参数时先核对生成结果

## 数据库迁移约定

- 建表 / 改表只走 Flyway：脚本放 `src/main/resources/db/migration`，命名 `V<版本>__<描述>.sql`
- JPA `ddl-auto` 固定为 `validate`（见 `application.yml`），禁止改为 `update` / `create` / `create-drop`；实体与表结构不一致时启动即失败，先补迁移
- **已执行脚本不可变**：某个版本只要在任一环境的数据库中执行过（本地 / 测试 / 生产），就禁止修改其内容、文件名或版本号；Flyway 校验和不匹配时应排查，不得用 `repair` 掩盖
- **回退只能前进**：撤销 / 修正已执行的迁移，必须新增更高版本脚本（如 `V<N+1>__revert_xxx.sql`）做补偿；禁止删除历史脚本，禁止用 `flyway clean` 清库重来（社区版无 undo）
- 仅当脚本尚未在任何数据库执行过（如尚未合并）时，才允许修改；合并后即视为已发布
- **预发布阶段同样适用**（根 `AGENTS.md`「阶段策略」）：改动迁移前先查目标库的 `flyway_schema_history`；未执行可改，已执行只能追加。库里有需要保留的数据（含远程开发库）即视为已执行，不存在"开发环境可以随意重写迁移"的例外
- **迁移内 INSERT 的新行 id 一律随机生成**（`gen_random_uuid()`，PostgreSQL 13+ 内置，无需 pgcrypto）：不手挑 / 不复制 UUID，不维护"编号式"id 序列（如 `0000…0010 / 0020` 递增编号）——手编号在后续迁移里极易误撞主键；行间引用按业务码子查询（如 `(select id from sys_menu where code = 'admin:user')`），不硬编码具体 id（同一迁移内先插入的行对后续语句可见）。V1 预置超管的固定 UUID 为设计文档 §4 的规定，属既成事实

## 查询约定
- 列表查询未显式指定排序规则时，默认按创建时间降序（`create_time DESC`）
- 需要其他排序的场景（如按更新时间、置顶加权）须在对应设计 / 接口契约中显式说明
- **不写手写 JPQL**（`@Query` / `@Modifying` 一律不用），查询实现方式固定为：
  - 简单条件 → 派生查询方法；复合主键关联表用 `@IdClass` 扁平主键（字段即实体属性，方法名保持简明）；如遇 `@EmbeddedId` 实体，遍历点用下划线标注（如 `findById_UserId`）
  - 跨表 join / distinct / 聚合 / 动态条件 → QueryDSL（`JPAQueryFactory`，见 `QuerydslConfig`）
  - 写操作一律实体态（加载 → 改状态 → 提交时脏检查落库）；集合全量替换 = `deleteAll` + **显式 `flush`** + `saveAll`（Hibernate 的 flush 顺序为先 insert 后 delete，同 PK"删除 + 重插"不 flush 会撞主键）
  - 确需手写 JPQL 的例外（如超大表批量 DML、数据库方言特性）：在代码注释说明理由，并回到本文件补记
- **软删除数据的例外读取**：回收站、未来桌面同步墓碑需要读取 `delete_flag = 1` 的行，不能移除实体级 `@SQLRestriction`；在专用 Repository 中用原生 SQL / `JdbcTemplate` 实现，并在代码注释与对应设计文档说明理由
