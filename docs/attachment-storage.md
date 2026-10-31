# 附件存储设计（本地 / R2 / 数据库）

> **状态：v1.0 · 2026-10-31（已实现：LOCAL / R2 / DATABASE 三介质、私有 R2 + Nginx auth_request 方案 B、任意文件、20MB、运行时切换；编译 / 前端 typecheck / 单测通过，Testcontainers 集成测试待 Docker 环境）**
> 范围：附件元数据与二进制的分离、三种存储介质的抽象与切换、任意文件策略、DIRECT 访问路径、独立媒体域、公开 / 私有 R2 访问链路、滥用防护、迁移与校验
> 关联：`docs/content-model.md`（附件表与媒体接口）、`docs/tech-stack.md`（R2 选型与机密配置）、`backend/AGENTS.md`（数据模型 / 迁移 / 权限约定）
> 非目标：浏览器直传、历史文件跨介质自动搬迁、图片派生、附件回收站、桌面端离线附件队列

## 0. 结论与本次决策

**可行。** 现有 `attachment` 元数据表、`AttachmentStorage` SPI 和 `/api/media/{id}` 都可以继续使用。需要调整的是「存储介质按行记录」、二进制改为流式 I/O，以及把 OSS 数据面交给同域 Nginx + 公开 R2。

| 决策点 | 结论 | 设计影响 |
| --- | --- | --- |
| 对象存储 | **Cloudflare R2**，S3 兼容适配器 | 不引入第二套 OSS SDK；R2 / MinIO 共用实现 |
| R2 bucket | **私有（方案 B 已实现）** | 公开 bucket 可被外部直接刷；私有才能让媒体域 / Nginx 成为唯一入口（见 §10.3） |
| 媒体域 | **独立媒体域（建议采用）** | 隔离 Cookie / 同源 / XSS；便于单独限流、缓存、WAF；但它本身不防热点盗链（见 §10.1） |
| 切换方式 | **运行时切换** | 全局 `storage.mode` 存 `site_setting`；仍需行级 `storage_mode` |
| 文件范围 | **任意文件** | 不是「没区别」：需要文件策略、下载语义、编辑器插入方式、封面仅限图片 |
| DATABASE 上限 | **20 MB** | 调大 multipart 上限；二进制必须流式读写，不能整块 `byte[]` |
| OSS 访问 | **DIRECT 外链 + 媒体域 Nginx + auth_request 预签名** | OSS 文件 URL 是 `/oss/{key}`；Nginx 向后端取预签名 URL 并代理 R2，浏览器只看到媒体域 |

### 0.1 必须先明确的三个点

1. **任意文件不是「没区别」。**
   - 安全：HTML / SVG / JS / XML 如果同源 inline 返回，会形成 XSS；必须强制下载或放到独立媒体域。
   - 体验：图片要缩略图、`![]()`；其他文件要文件卡片、`[]()` 链接、下载按钮。
   - 业务：`cover_attachment_id` 只能接受图片；`width` / `height` / `alt_text` 只对图片有意义。
   - 校验：不能再只做图片 MIME 白名单，需要魔数检测 + 危险类型的内联禁用策略。
2. **20 MB 的数据库附件必须流式读写。**
   - `MultipartFile.getBytes()` + JPA `byte[]` + JDBC 缓冲会产生数倍内存峰值；
   - DATABASE 适配器建议用 `JdbcTemplate` + `PreparedStatement.setBinaryStream` / `getBinaryStream`，绕过 JPA 大字段映射（理由与例外需记录到 `backend/AGENTS.md`）；
   - `spring.servlet.multipart.max-file-size` / `max-request-size` 必须同步提高到 20 MB / 25 MB。
3. **公开 R2 bucket 下 Nginx 可直接代理，但不是防刷方案。**
   - 公开 bucket 下，Nginx `proxy_pass` 到公开域即可，不需要 SigV4；
   - 但公开 bucket 可被外部直连刷量；**复评建议改为私有 bucket**。私有 bucket 下标准 Nginx 无法直接代理，只能：
     - 后端生成 R2 预签名 URL 并 302（此时数据不经过 Nginx），或
     - 使用 OpenResty / Lua 做签名（运维复杂度上升），或
     - 使用 Cloudflare Worker / Access 前置。
   - 公开 bucket 的安全边界见 §10：随机 UUID、独立媒体域（建议）、非图片强制下载。

### 0.2 关于「公开 bucket 被刷」和「独立媒体域」的结论

- **公开 bucket 确实可能被外部刷。** 只要知道 key，就可以绕过 Nginx 直接请求 R2 公开域；Nginx 的缓存、限流、`valid_referers` 都保护不到 R2 源站。随机 UUID 只能降低被猜中的概率，不能防热点盗链、爬虫、恶意刷流量。
- **独立媒体域有价值，但它不是防刷方案。** 它真正解决的是 **origin 隔离**：Cookie 隔离、同源 XSS 隔离、独立 CSP / 安全头、独立缓存 / WAF / 日志。公开媒体域仍然可以被刷，只是刷的是媒体域而不是主站。
- **真正的防刷组合**：
  1. **私有 R2 bucket**，让 R2 源站不可直接访问；
  2. 媒体域只暴露 Nginx（或 Nginx + 后端签名），所有请求必须经过限流 / 缓存 / WAF；
  3. Nginx `limit_req` / `limit_conn` / `proxy_cache`，Cloudflare WAF / Rate Limiting；
  4. 短时签名 URL 或 Nginx 侧签名，避免长期可复制的直链；
  5. R2 用量 / 账单告警，异常流量可发现；
  6. 随机 key、禁止目录列举、非图片强制下载。
- **推荐顺序（综合性价比）**：
  - **私有 R2 + Nginx auth_request 预签名代理（方案 B，首选）**：标准 Nginx，浏览器始终在媒体域；Nginx 可缓存、限流、加安全头、隐藏 R2；代价是每个请求一次轻量签名子请求 + 配置复杂度。
  - **私有 R2 + 后端预签名 302（方案 A，最小回退）**：最简单；浏览器最终直连 R2；Nginx 不缓存文件体、不能给最终响应加安全头；适合低流量 / Nginx 带宽有限。
  - **私有 R2 + OpenResty/Lua SigV4（方案 C）**：Nginx 数据面最完整，无后端签名请求；需要 OpenResty / Lua、凭据管理、时钟同步，运维成本最高。
  - **公开 R2 + 媒体域 + WAF/限流/缓存/告警**：最便宜、最简单，但无法阻止直接打 R2 源站，只适合低风险个人内容。

一句话：**元数据一张表，二进制三种介质，写路径看全局设置，读路径看行记录；防刷靠私有源站 + 签名 + 限流，独立媒体域负责隔离，不负责挡住刷量。**

---

## 1. 现状与差距

现有实现（`domain/Attachment`、`service/AttachmentService`、`service/AttachmentStorage`、`service/LocalAttachmentStorage`）：

- `attachment.storage_key` 已与访问 URL 解耦，公开地址固定为 `/api/media/{id}`；
- 只有本地适配器，`AttachmentStorage` 由 Spring 注入唯一实现；
- 只允许 PNG / JPEG / GIF / WebP / AVIF，单文件 10 MB；
- 没有记录二进制实际存放在哪种介质；
- `AttachmentService.upload` 先写文件再保存元数据，删除时软删元数据并删除文件；
- 前端媒体库按「图片」设计（缩略图、`accept` 图片类型、编辑器图片上传）。

差距：

1. **切换语义**：不能只用一个全局模式同时决定读写，否则切换后历史附件 404；
2. **文件策略**：任意文件需要重新定义安全边界与前端交互；
3. **I/O 模型**：20 MB 与 Nginx 代理要求流式，而不是 `byte[]`；
4. **访问链路**：OSS 需要 Nginx 数据面，但要与稳定 URL、运行时切换兼容。

---

## 2. 目标与非目标

### 2.1 目标

1. 一张 `attachment` 表同时承载本地、R2、数据库三种介质的元数据。
2. 调用方只依赖 `AttachmentService`，不感知介质；上传返回可访问 URL（DIRECT）。
3. 管理端可运行时切换「新上传默认介质」，切换后历史附件仍可读、可删。
4. 支持任意文件，但默认只对图片 inline，其余强制下载。
5. OSS 数据面由同域 Nginx 直接代理公开 R2，后端不转发大文件。
6. DATABASE 模式支持 20 MB 文件，读写不产生不可控的堆内存峰值。
7. 启动 / 切换时对不可用配置 fail-fast 或明确拒绝。
8. 保留 `/api/media/{id}` 稳定端点，作为 OSS 直链的兜底 / 兼容入口。

### 2.2 非目标

- 不做浏览器直传 R2 / 预签名上传；
- 不做历史文件跨介质自动搬迁（P1 独立任务）；
- 不做图片多尺寸、压缩、转码；
- 不做附件回收站与恢复（沿用当前「删除即删二进制」语义）；
- 不做 Range / 断点续传的完整支持（P1；Nginx 代理 R2 天然支持 Range，后端本地 / DB 首版不承诺）；
- 不做桌面端本地附件库 / 离线队列；
- 不做多租户 bucket。

---

## 3. 总体架构

```text
浏览器 / 桌面端
  │
  │  https://example.com/api/admin/media      （上传 / 列表 / 删除）
  │  https://example.com/api/media/{id}       （LOCAL / DATABASE）
  │  https://example.com/oss/{key}            （OSS 直链）
  ▼
Nginx（与页面同域）
  ├─ /api/admin/media/**  → Spring 后端
  ├─ /api/media/**        → Spring 后端
  │       ├─ LOCAL / DATABASE：后端流式返回
  │       └─ OSS：302 → /oss/{storageKey}（稳定端点兜底）
  └─ /oss/**              → R2（公开：Nginx 直连；私有：签名后代理 / 302）（proxy_cache）
```

**DIRECT 模式下：**

- `LOCAL` / `DATABASE`：`AttachmentResponse.url = /api/media/{id}`，Nginx 转发到后端；
- `OSS`：`AttachmentResponse.url = /oss/{storageKey}`，Nginx 直接代理公开 R2，**不经过后端**；
- `/api/media/{id}` 对 OSS 附件仍返回 302 到 `/oss/{key}`，用于历史数据 / 手写链接 / 调试。

应用与媒体同域时，Nginx 只做路径分流：

```text
location /api/media/  → backend
location /oss/        → R2
```

### 3.1 职责边界

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `AttachmentService` | 文件策略、元数据、权限、引用保护、可访问 URL | 具体介质读写、Nginx 路由 |
| `AttachmentStorageRouter` | 按模式选择适配器、启动 / 切换校验 | 业务权限、URL 拼装 |
| `AttachmentStorage` 实现 | 单一介质的流式 store / load / delete | 事务边界、审计字段 |
| `MediaController` | 本地 / DB 流式响应；OSS 稳定端点 302；缓存头 / Content-Disposition | 介质选择 |
| `MediaUrlResolver` | 生成 DIRECT / STABLE URL（可选绝对地址） | 介质读写 |
| `StorageAdminService` | 模式读取 / 切换、健康检查、介质计数 | 文件内容处理 |
| Nginx | `/oss/**` 代理公开 R2、`/api/media/**` 转发后端、缓存、Range、TLS | 业务权限、附件元数据 |

### 3.2 三种介质的取舍

| 维度 | LOCAL | R2（S3 兼容） | DATABASE |
| --- | --- | --- | --- |
| 适用场景 | 本地开发、单机部署 | 生产、多实例、Nginx/CDN 前置 | 小文件、无外部依赖、随库备份 |
| 多实例 | **不适合**（每实例磁盘不同） | 适合 | 适合（共享库） |
| 数据面 | Nginx → 后端 | **Nginx → R2（不经过后端）** | Nginx → 后端 |
| 备份 | 备份目录 / 卷 | bucket 版本 / 生命周期 | 随数据库 dump，库会变大 |
| 单文件上限 | 20 MB | 20 MB | **20 MB** |
| 主要风险 | 节点漂移、卷丢失 | 公开 bucket 被直连刷量、Nginx / 签名配置 | 库膨胀、连接池与内存 |

结论：**LOCAL 仅用于开发 / 单机；生产优先 R2；DATABASE 作为无外部依赖的兜底。** 多实例部署禁止 LOCAL。

---

## 4. 数据模型

### 4.1 `attachment` 增量

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `storage_mode` | `varchar(16) NOT NULL` | `LOCAL` / `OSS` / `DATABASE`，记录该附件二进制实际所在介质 |

- 新增列必须回填现有数据为 `LOCAL`；
- `storage_key` 语义不变：在所选介质内的唯一 key，仍为随机 UUID + 安全扩展名；
- 任意文件不需要新增「类型」列：`mime_type` 已足够；图片专用字段 `width` / `height` / `alt_text` 对其他文件保持 `NULL`；
- 不在 `attachment` 上存 bucket / endpoint / 绝对路径：这些由介质配置决定；
- 公开 DTO 不返回 `storage_mode` / `storage_key`。

### 4.2 `attachment_blob`（仅 DATABASE 模式使用）

```sql
create table attachment_blob
(
    storage_key varchar(255) not null primary key,
    content     bytea        not null,
    create_time timestamptz  not null,
    constraint fk_attachment_blob_attachment
        foreign key (storage_key) references attachment (storage_key) on delete cascade
);

comment on table attachment_blob is '数据库模式的附件二进制；仅通过 DatabaseAttachmentStorage 访问';
```

设计理由：

- `attachment` 是高频列表 / 引用检查表，二进制放独立表可避免列表查询误加载大字段；
- 独立表让 DATABASE 适配器的读写边界清晰；
- 外键指向 `attachment.storage_key`（已有唯一约束），物理删除时可级联；软删除不会触发级联。

**20 MB 的实现约束：**

- 不使用 JPA `byte[]` / `@Lob` 实体映射，改用 `JdbcTemplate`：
  - 写：`PreparedStatement.setBinaryStream(...)`；
  - 读：`ResultSet.getBinaryStream(...)`；
  - 长度：`octet_length(content)` 或直接使用 `attachment.size_bytes`；
- 这样可以避免 Hibernate 把 20 MB 读入 `byte[]` 后再复制，也避免 `@Lob` 在 PostgreSQL 上映射成 `oid`；
- 这属于「原生 SQL / JdbcTemplate」例外，需在代码注释与 `backend/AGENTS.md` 说明理由。

### 4.3 迁移脚本（建议）

`V11__attachment_storage_modes.sql`：

```sql
alter table attachment add column storage_mode varchar(16);

-- 现有数据全部由本地适配器写入；如某环境已手工接过 OSS，需在执行前单独确认
update attachment set storage_mode = 'LOCAL' where storage_mode is null;

alter table attachment alter column storage_mode set not null;
alter table attachment add constraint ck_attachment_storage_mode
    check (storage_mode in ('LOCAL', 'OSS', 'DATABASE'));

create index idx_attachment_storage_mode
    on attachment (storage_mode) where delete_flag = 0;

create table attachment_blob
(
    storage_key varchar(255) not null primary key,
    content     bytea        not null,
    create_time timestamptz  not null,
    constraint fk_attachment_blob_attachment
        foreign key (storage_key) references attachment (storage_key) on delete cascade
);
```

如果采用 §7.2 的独立存储管理端点，再追加：

`V12__add_storage_permission.sql`：在 `sys_menu` 的 `admin:media` 下插入 `admin:media:storage`（ACTION），id 用 `gen_random_uuid()`，父节点按 code 子查询。

执行前按 `backend/AGENTS.md` 查目标库 `flyway_schema_history`；V11 一旦在任一需要保留数据的库执行，不再修改。版本号以实际实现顺序为准，若 V11 已被占用则顺延。

---

## 5. 存储抽象

### 5.1 SPI（流式）

```java
public interface AttachmentStorage {

    StorageMode mode();

    void store(String storageKey, InputStream content, long size, FileMeta meta);

    StoredObject load(String storageKey);

    void delete(String storageKey);

    /** 启动 / 切换时校验配置与可用性；默认无操作 */
    default void validate() {
    }

    record FileMeta(String contentType, String contentDisposition, String cacheControl) {
    }

    record StoredObject(InputStream content, long size, String contentType, String contentDisposition) {
    }
}
```

约定：

- `store` 不做覆盖写；key 由调用方生成，重复即异常；
- 流由调用方打开；实现读取但不负责关闭（或统一在 `AttachmentService` try-with-resources 关闭）；
- `load` 找不到时抛 `ApiException.notFound("附件文件不存在")`；
- `delete` 幂等；
- 实现类不开启独立事务；DATABASE 实现参与 `AttachmentService` 的事务；
- 所有实现只处理 `storage_key`，不感知 `attachment.id`、owner、权限。

### 5.2 三种实现

| 实现 | 关键点 |
| --- | --- |
| `LocalAttachmentStorage` | 根目录 `MEDIA_LOCAL_ROOT`；`normalize()` 后必须仍在根目录内；写入临时文件后原子移动；`Files.newInputStream` 流式读取；启动校验目录可写 |
| `R2AttachmentStorage` | AWS SDK v2 `S3Client`；endpoint / region / bucket / path-style / key-prefix 可配；`putObject` 设置 `ContentType` / `ContentDisposition` / `CacheControl`；`getObject` 返回流；`deleteObject`；健康检查 `headBucket` 或探针对象 `headObject` |
| `DatabaseAttachmentStorage` | `JdbcTemplate` + `setBinaryStream` / `getBinaryStream`；`octet_length` 取大小；20 MB 上限；与元数据同事务 |

R2 配置建议（沿用 `docs/tech-stack.md` 的 `${}` 外部值策略）：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB       # 与附件上限一致
      max-request-size: 25MB    # 留 multipart 开销

simple-notes:
  storage:
    max-bytes: "${STORAGE_MAX_BYTES:20971520}"          # 全局 20 MB
    public-base-url: "${MEDIA_PUBLIC_BASE_URL:}"        # 例如 https://media.example.com；空则返回相对 URL
    url-mode: "${STORAGE_URL_MODE:DIRECT}"              # DIRECT（默认）/ STABLE，见 §7.1
    mode: "${STORAGE_MODE:LOCAL}"                       # site_setting.storage.mode 缺失时的兜底
    internal-token: "${MEDIA_INTERNAL_TOKEN:}"          # Nginx auth_request 共享令牌
    local-root: "${MEDIA_LOCAL_ROOT:${java.io.tmpdir}/simple-notes-media}"
    database:
      max-bytes: "${STORAGE_DB_MAX_BYTES:20971520}"     # 20 MB
    r2:
      endpoint: "${R2_ENDPOINT}"
      region: "${R2_REGION:auto}"
      bucket: "${R2_BUCKET:simple-notes}"
      access-key: "${R2_ACCESS_KEY_ID}"
      secret-key: "${R2_SECRET_ACCESS_KEY}"
      path-style: "${R2_PATH_STYLE:true}"
      key-prefix: "${R2_KEY_PREFIX:attachments}"
      presign-ttl: "${R2_PRESIGN_TTL:5m}"
      signing: "${R2_SIGNING:auth-request}"             # auth-request（B）/ backend-302（A）/ backend-proxy（本地开发）
      edge: "${R2_EDGE:nginx}"                          # nginx / backend
      public-base-url: "${R2_PUBLIC_BASE_URL:}"         # 仅公开 bucket / Nginx upstream 用
```

**两个 R2 地址不要混用：**

- `R2_ENDPOINT`：S3 API 端点（`https://<account>.r2.cloudflarestorage.com`），后端 SDK 用它上传 / 删除，需要 access key / secret；
- `R2_PUBLIC_BASE_URL`：公开 bucket（`https://pub-xxx.r2.dev`）或绑定到 bucket 的自定义域，**只给 Nginx 做 `proxy_pass`**，不需要 R2 凭据；
- `MEDIA_PUBLIC_BASE_URL`：返回给前端的媒体 origin（同域时可为空，使用相对路径；独立媒体域时填 `https://media.example.com`），**不是 R2 地址**；
- 把 Nginx 指到 `R2_ENDPOINT` 会因缺少 SigV4 签名而失败；
- 本设计**采用私有 bucket**。公开 bucket 只适合低风险内容；私有 bucket 的 `DIRECT` 直链需要后端预签名、Nginx `auth_request` 或 OpenResty 签名（见 §7.3）。

- `storage.mode` 的最终来源见 §6；`application.yml` 只作为数据库无该键时的兜底。优先级：`site_setting.storage.mode` > `simple-notes.storage.mode` > `LOCAL`。
- R2 凭据缺失不阻塞 LOCAL / DATABASE 启动；但当前模式为 OSS 时，上传前 `validate()` 必须失败并给出明确错误。`R2AttachmentStorage` 构造时不能因缺凭据直接抛异常，否则 LOCAL 模式也无法启动。
- 健康检查优先 `headBucket`；如果 R2 API Token 没有 ListBucket 权限，改用配置的探针对象 `headObject`。
- 依赖：`software.amazon.awssdk:s3`（Apache 2.0）。不引入 Spring Cloud AWS 自动配置，避免把连接配置绑死在 `spring.cloud.aws.*`。

### 5.3 路由器

```java
@Component
public class AttachmentStorageRouter {

    private final Map<StorageMode, AttachmentStorage> storages;
    private final StorageModeProvider modeProvider;

    public AttachmentStorageRouter(List<AttachmentStorage> implementations,
            StorageModeProvider modeProvider) {
        // 重复 mode → 启动失败
        // 缺失 mode 不立即失败：R2 配置可能未启用
    }

    /** 新上传：读全局设置 */
    public AttachmentStorage forWrite() {
        return forRead(modeProvider.current());
    }

    /** 历史读取 / 删除：读附件行 */
    public AttachmentStorage forRead(StorageMode mode) {
        AttachmentStorage storage = storages.get(mode);
        if (storage == null) {
            throw new ApiException("存储介质未启用：" + mode);
        }
        return storage;
    }
}
```

- 不用 `@Primary` 决定实现；所有实现以 `mode()` 注册，重复直接 fail-fast；
- `forWrite` 每次上传读取一次模式，切换后下一次上传立即生效；上传过程中切换不影响本次；
- `forRead` 永远使用附件行上的模式，不读全局设置。

---

## 6. 系统设置与切换语义

### 6.1 设置键

复用现有 `site_setting` KV 表，新增键：

| 键 | 类型 | 默认 | 公开 | 允许值 |
| --- | --- | --- | --- | --- |
| `storage.mode` | STRING | `LOCAL` | 否 | `LOCAL` / `OSS` / `DATABASE` |

- 复用 `SiteSettingCatalog` 的定义 / 校验 / 管理端展示能力，**不新增第二张 KV 表**；该键标记为系统托管（`MANAGED_KEYS`），只能通过存储管理接口写入；
- `publicReadable = false`，不进入博客公开配置；
- 读取路径建议由 `StorageModeProvider` 直接依赖 `SiteSettingRepository`（只读），避免与 `SiteSettingService` 的写入逻辑形成循环依赖；
- `SiteSettingService.update` 目前对托管键返回的是主题专用文案，需要改成通用文案；
- 若未来运维配置项增多，再评估抽象为通用 `system_setting`。

### 6.2 切换流程

```text
PUT /api/admin/media/storage  { "mode": "OSS", "version": 123 }
  │
  ├─ 校验 mode 合法
  ├─ router.forRead(mode).validate()      // 本地目录可写 / R2 headBucket / DB 可读
  ├─ 统计旧模式附件数量（提示：切换不迁移历史文件）
  ├─ 写入 site_setting.storage.mode（带 update_by / update_time）
  └─ 返回 { mode, counts, health }
```

规则：

1. **切换只改写入目标**。已有附件继续按行上的 `storage_mode` 读取；
2. 切到 R2 前必须通过健康检查，避免写入后才发现凭据 / bucket 错误；
3. 从 R2 切走前提示「旧 R2 附件仍依赖 R2 配置与 Nginx `/oss/` 路由」；
4. 切换不做批量搬迁；需要搬迁时另立 P1 任务；
5. 设置写入使用乐观版本（可选）：请求带 `version`，冲突返回 409。

### 6.3 启动校验

`StorageStartupValidator`（`ApplicationRunner`）在 Flyway / JPA 就绪后执行：

- `storage.mode` 无法解析 → **启动失败**；
- 当前模式对应的实现不存在 → **启动失败**；
- LOCAL：根目录不存在则创建，不可写 → **启动失败**；
- DATABASE：`attachment_blob` 表可访问 → 失败即启动失败；
- R2：配置缺失 → **启动失败**；网络 / bucket 暂不可达 → 记录 WARN，不阻塞启动（切换时仍会硬校验）。

---

## 7. 访问路径与 Nginx

### 7.1 URL 策略：DIRECT 为默认

**已选 `DIRECT`：上传响应直接返回可访问 URL；OSS 不经过后端。**

```text
storage.url-mode = DIRECT

LOCAL / DATABASE:
  AttachmentResponse.url = {MEDIA_PUBLIC_BASE_URL}/api/media/{id}
  GET /api/media/{id} → Nginx → 后端 → 流式返回

OSS:
  AttachmentResponse.url = {MEDIA_PUBLIC_BASE_URL}/oss/{storageKey}
  GET /oss/{storageKey} → Nginx → R2（不经过后端）
```

同时保留稳定端点：

```text
GET /api/media/{id}
  ├─ LOCAL / DATABASE → 200，后端流式返回
  └─ OSS → 302 Location: /oss/{storageKey}
           └─ Nginx /oss/ → R2
```

稳定端点的用途：

- 兼容历史上已经写入 `/api/media/{id}` 的正文 / 封面；
- 手写链接、调试、后续从 DIRECT 切回 STABLE；
- 不改变 DIRECT 模式下 OSS 直链不经过后端的事实。

**DIRECT 的代价：**

- URL 与介质绑定；历史链接依赖「旧介质 + Nginx 路由」继续存在；
- 从 OSS 切到 DATABASE 后，旧 OSS 直链仍指向 R2；只要 R2 bucket 和 `/oss/` 路由保留就可用；
- 退役 R2、删除 `/oss/` 或改 bucket 时，旧直链会失效；
- 如果未来需要「切换介质不坏历史链接」，再切回 `STABLE` 并把新内容写成 `/api/media/{id}`；旧直链不会自动改写。

**可选 `STABLE`：**

- `AttachmentResponse.url` 恒为 `/api/media/{id}`；
- OSS 由后端 302 到 `/oss/{key}`；
- 好处是 URL 与介质解耦，代价是多一次 302。

### 7.2 媒体域 Nginx 配置（方案 B）

完整示例见 `deploy/nginx/media.conf.example`。核心是：

```nginx
location = /_media_presign {
    internal;
    proxy_pass http://simple_notes_backend/api/internal/media/presign?key=$uri;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header X-Internal-Token "MEDIA_INTERNAL_TOKEN";
}

location /oss/ {
    limit_req zone=simple_notes_media_rl burst=40 nodelay;
    auth_request /_media_presign;
    auth_request_set $r2_url $upstream_http_x_r2_url;
    auth_request_set $r2_host $upstream_http_x_r2_host;

    resolver 1.1.1.1 valid=300s;
    proxy_pass $r2_url;
    proxy_set_header Host $r2_host;
    proxy_ssl_server_name on;
    proxy_ssl_name $r2_host;

    proxy_cache simple_notes_media;
    proxy_cache_key "$scheme$request_method$host$uri";
    proxy_cache_valid 200 206 30d;
    proxy_cache_lock on;

    add_header X-Content-Type-Options nosniff always;
    add_header Content-Security-Policy "sandbox" always;
}
```

说明：

- `/oss/` 不直接 `proxy_pass` 到 R2；先 `auth_request` 让后端返回短期预签名 URL；
- 浏览器只看到媒体域，R2 endpoint / 签名不暴露；
- `proxy_cache_key` 忽略签名 query，缓存文件体；
- Nginx 缓存删除后仍可能命中旧对象；附件 key 不可变，删除时需要短 TTL / purge / 墓碑（P1）；
- `auth_request` 通常会每个请求执行一次；后端签名接口只做轻量校验与 HMAC。

### 7.3 私有 R2 的替代方案（防刷推荐）

如果担心公开 bucket 被刷，R2 应改为私有。此时 `AttachmentResponse.url` 仍是媒体域的 `/oss/{key}`，但 Nginx / 媒体域需要拿到 R2 的授权，不能再 `proxy_pass` 到公开域。

| 方案 | 数据面 | 防刷能力 | 复杂度 | 适用 |
| --- | --- | --- | --- | --- |
| **私有 + 后端预签名 302** | 浏览器 → R2 | R2 源站私有；签名短时有效；Nginx 可限流入口 | 低 | 推荐首选；接受 Nginx 不缓存文件体 |
| **私有 + Nginx auth_request 预签名代理** | Nginx → R2 | 源站私有；Nginx 缓存 / 限流；每次请求需后端签名子请求 | 中 | 想要 Nginx 缓存 + 数据面 |
| **私有 + OpenResty/Lua SigV4** | Nginx → R2 | 源站私有；Nginx 缓存 / 限流 / 签名全在 Nginx | 高 | 流量较大、愿意维护 Lua / 凭据 |
| 公开 + Nginx + WAF/限流/缓存 | Nginx → R2 | Nginx 入口可限流；**R2 源站仍可被直连** | 低 | 低风险个人内容，接受源站暴露 |
| Cloudflare Worker / Access | Cloudflare → R2 | 源站私有；边缘鉴权 / 限流 | 中 | 不要求纯 Nginx |

**推荐顺序（综合性价比）：**

1. **私有 R2 + Nginx auth_request 预签名代理（B，首选）**：
   - 浏览器请求 `https://media.example.com/oss/{key}`；
   - Nginx `auth_request` 调后端内部接口，后端返回 `X-R2-Url`；
   - Nginx `proxy_pass $r2_url` 代理 R2，可继续 `proxy_cache` / `limit_req` / 安全头；
   - 浏览器始终停留在媒体域，R2 endpoint / 签名不暴露；
   - 代价：每个请求一次轻量签名子请求；`resolver`、变量 `proxy_pass`、内部接口保护等配置比 302 复杂。
2. **私有 R2 + 后端预签名 302（A，最小回退）**：
   - Nginx（或后端）校验 / 限流后返回 302 到短期预签名 R2 URL；
   - 浏览器直连 R2 取文件，后端和 Nginx 都不转发 20 MB；
   - 最简单，但 Nginx 不能缓存文件体、不能给最终响应加安全头，浏览器最终地址是 R2 域名；
   - 适合低流量或 Nginx 带宽有限。
3. **私有 R2 + OpenResty/Lua（C）**：
   - Nginx 自己签 SigV4，不依赖后端；
   - 需要 OpenResty / Lua 模块、R2 凭据管理、时钟同步；
   - 数据面最完整，但运维成本最高。

**A/B/C 的边际成本：**

- A 和 B 每个媒体请求都需要一次后端签名（A 是 302，B 是 `auth_request` 子请求）；A 可以通过短时缓存 302 降低后端 QPS，B 的 `auth_request` 通常仍会每请求执行。
- B 相对 A 增加的边际成本主要是 **Nginx 带宽 / 连接数 / 缓存磁盘**，换来的是：文件体缓存、最终响应安全头、全链路限流、隐藏 R2 地址。
- C 把签名也移到 Nginx，去掉后端每请求签名；代价是 OpenResty / Lua / 凭据管理。
- 对个人项目：如果 Nginx 带宽够，B 的收益通常大于成本；如果 Nginx 在小带宽 VPS 上，A 把字节直接交给 R2 更省。

**公开 bucket 的定位：**

- 如果只是个人博客图片、可以接受「知道 URL 就能访问」，公开 bucket + 独立媒体域 + Cloudflare WAF / Nginx 限流 / 缓存 / 账单告警 是成本最低的方案；
- 但它**不能阻止直连 R2 源站**，不适合作为「防刷」方案；
- 一旦附件包含敏感文件，直接选私有 + 预签名，不要用公开 bucket。

**DIRECT 与私有 bucket 的关系：**

- `AttachmentResponse.url` 仍可保持 `/oss/{key}`；
- 私有 bucket 下这一路径由 Nginx / 后端签名后 302 或代理到 R2；
- 浏览器看到的仍是媒体域 URL，不会暴露 R2 endpoint / 签名；
- 如果直接返回预签名 R2 URL 作为正文链接，URL 会过期，不适合写入 Markdown。
- A 回退建议使用 `url-mode=STABLE` + `signing=backend-302`：正文仍写 `/api/media/{id}`，后端直接 302 到预签名 R2，不需要额外的按 key 签名公开接口。

### 7.4 对外接口

| 方法 / 路径 | 说明 |
| --- | --- |
| `GET /api/media/{id}` | 匿名可读；LOCAL / DB 流式返回，OSS 302 到 `/oss/{key}`（稳定端点 / 兜底） |
| `GET /oss/{key}` | OSS 直链；公开 bucket 由 Nginx 直接代理，私有 bucket 由 Nginx / 后端签名后代理或 302 |
| `POST /api/admin/media` | 上传；`multipart/form-data`，字段 `file`；任意文件 |
| `GET /api/admin/media` | 媒体库列表 |
| `DELETE /api/admin/media/{id}` | 删除（软删元数据 + 清理二进制） |
| `GET /api/admin/media/storage` | 当前模式、可用模式、计数、健康状态（建议新增） |
| `PUT /api/admin/media/storage` | 运行时切换（建议新增） |

`AttachmentResponse.url`：

- `DIRECT`（默认）：LOCAL / DB 为 `/api/media/{id}`，OSS 为 `/oss/{key}`（或带 `MEDIA_PUBLIC_BASE_URL` 的绝对地址）；私有 bucket 下 `/oss/{key}` 由 Nginx / 后端签名后处理，浏览器不暴露 R2 endpoint；
- `STABLE`（可选）：恒为 `/api/media/{id}`，OSS 由后端 302 到 `/oss/{key}`。

### 7.5 存储管理接口（建议）

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/admin/media/storage` | `admin:media` | 当前模式、可用模式、各模式附件数量、健康状态 |
| `PUT /api/admin/media/storage` | `admin:media:storage` | 切换新上传介质；body `{mode, version?}` |

`StorageStatusResponse` 建议字段：

```json
{
  "mode": "LOCAL",
  "availableModes": ["LOCAL", "OSS", "DATABASE"],
  "counts": { "LOCAL": 12, "OSS": 3, "DATABASE": 0 },
  "health": { "LOCAL": "OK", "OSS": "MISSING_CREDENTIALS", "DATABASE": "OK" }
}
```

- 计数按 `storage_mode` 分组、只统计未删除附件；
- 健康状态只暴露安全摘要，不返回 endpoint / bucket / 凭据；
- 响应 DTO 补 `@Schema(requiredProperties = ...)`，改完跑 `pnpm gen:api`；
- 新增权限码 `admin:media:storage`：`Perms` 加常量 + `V12` 插入 `sys_menu`（ACTION，父节点 `admin:media`）；启动对账会自动校验。

---

## 8. 任意文件策略

### 8.1 文件类型与响应方式

| 类型 | 存储 | 返回方式 | 前端 |
| --- | --- | --- | --- |
| 图片（PNG / JPEG / GIF / WebP / AVIF） | 允许 | `Content-Disposition: inline` | 缩略图；编辑器插入 `![]()` |
| PDF / 文档 / 压缩包 / 其他 | 允许 | `Content-Disposition: attachment` | 文件卡片；编辑器插入 `[]()` |
| SVG | **禁止** | — | 不允许上传（XSS） |
| HTML / XHTML / JS / CSS / XML | 可存储但**禁止 inline** | 强制 `attachment` | 仅下载，不预览 |
| 可执行文件 | 可存储但强制下载 | `application/octet-stream` + `attachment` | 仅下载 |

- 不能只信 `MultipartFile.getContentType()`；读取文件头做魔数校验：
  - PNG / JPEG / GIF / WebP 使用标准魔数；
  - AVIF 校验 `ftyp` brand；
  - 其他类型无法可靠识别时回落 `application/octet-stream`；
- 文件名不参与存储路径；`storage_key = UUID + 安全扩展名`，扩展名只用于展示 / 日志，不作为安全依据；
- `cover_attachment_id` 只接受图片：`AttachmentService.validateCover` 增加 `mimeType.startsWith("image/")` 检查；
- `width` / `height` / `alt_text` 仅图片填写，其他文件为 `NULL`。

### 8.2 前端影响

- 媒体库：
  - 图片显示缩略图；非图片显示文件图标、文件名、大小、MIME；
  - `accept` 从图片列表改为不限或按需；
  - 复制 URL、下载、删除；
- 编辑器：
  - 图片上传：插入 `![alt](url)`；
  - 非图片上传：插入 `[原始文件名](url)`；
  - Vditor 的图片上传回调只处理图片；任意文件需要单独的「上传文件」入口 / 自定义处理；
- 封面：
  - 前端限制 `accept="image/*"`，后端 `validateCover` 再校验，防止绕过；
- 媒体库文案从「上传图片」改为「上传文件」。

---

## 9. 关键流程与失败处理

### 9.1 上传

```text
1. 校验：非空、大小（全局 20 MB + 当前模式上限）、文件策略、魔数、禁止 SVG
2. mode = StorageModeProvider.current()
3. storage = router.forWrite(); storage.validate()（必要时）
4. key = UUID + 安全扩展名
5. 创建 Attachment（storageMode = mode, storageKey = key, mimeType, sizeBytes, ...）
6. attachmentRepository.saveAndFlush(attachment)   // DATABASE 模式需要先有元数据行满足外键
7. storage.store(key, inputStream, size, FileMeta(contentType, contentDisposition, cacheControl))
8. 提交事务；返回 AttachmentResponse（url 按 §7.1）
```

- **先元数据后二进制**：DATABASE 模式的 `attachment_blob` 有外键，必须先插入 `attachment`；同时保证元数据与 blob 在同一事务内原子提交。
- **外部介质补偿**：LOCAL / R2 写入成功后若事务提交失败，注册 `afterCompletion` 做 best-effort 删除；删除失败只记录日志，纳入孤儿清理。
- **DATABASE 模式**：`storage.store` 与元数据共用事务；任一步失败整体回滚。
- **流式**：`MultipartFile.getInputStream()` 直接交给存储实现；不要 `getBytes()`。魔数校验读前缀后重新打开流（`MultipartFile` 支持多次 `getInputStream()`）。
- **大小校验**：全局 20 MB；DATABASE 模式 20 MB；超限 400。
- **Content-Disposition**：图片 `inline`，其他 `attachment`；R2 对象写入时一并设置，Nginx 直接透传。

### 9.2 读取

```text
DIRECT 模式：
  LOCAL / DATABASE：
    GET /api/media/{id} → Nginx → 后端 → 200 流式响应

  OSS：
    GET /oss/{key} → Nginx → R2 → 200（不经过后端）

稳定端点 / 兜底：
  GET /api/media/{id}
    → 查 attachment（@SQLRestriction 已排除软删）
    → storage = router.forRead(attachment.storageMode)
    ├─ LOCAL / DATABASE：storage.load(key) → 200 流式响应
    └─ OSS + edge=nginx：302 Location: {mediaBase}/oss/{key}
       OSS + edge=backend：storage.load(key) → 200 流式响应（本地开发）
```

- 元数据不存在 → 404；
- 元数据存在但二进制缺失 → 404「附件文件不存在」，并记录 WARN；
- R2 网络 / 权限错误 → 503（介质暂不可用）；日志记录 key 与模式，不返回底层细节；
- `Content-Type` 来自 `attachment.mime_type`；`Content-Length` 来自 `attachment.size_bytes`；
- `Content-Disposition`：图片 `inline`，其他 `attachment`；文件名按 RFC 5987 编码；OSS 对象在 `putObject` 时写入同样的元数据；
- `X-Content-Type-Options: nosniff`；
- 缓存：本地 / DB 由后端设置 `Cache-Control: public, max-age=30d`；OSS 由 R2 元数据 + Nginx 缓存；
- Range：Nginx → R2 支持；后端本地 / DB 首版不承诺 Range，P1 再补（20 MB 上限下影响有限）。

### 9.3 删除

当前语义：软删元数据 + 立即删除二进制，不支持附件恢复。保留该语义：

1. 校验归属 / 超管；
2. 检查 `note.cover_attachment_id` / `note_revision.cover_attachment_id` 引用；
3. `markDeleted` 并保存；
4. 提交后按行 `storage_mode` 删除二进制：
   - DATABASE：同一事务内删除 blob（回滚安全）；
   - LOCAL / R2：提交后 best-effort 删除，失败记录日志；
5. Nginx 缓存：R2 对象删除后缓存可能仍命中；按 §7.2 的 TTL / purge 处理；
6. 孤儿二进制由 P1 清理任务处理。

**已知限制**：删除附件后，如果通过回收站恢复了引用它的笔记，封面 / 正文图片会 404。当前实现已有同样行为；若需要「恢复后图片仍在」，应改为延迟清理二进制（P1）。

### 9.4 切换介质

| 场景 | 行为 |
| --- | --- |
| LOCAL → R2 | 新上传写 R2，返回 `/oss/{key}`；旧 LOCAL 附件继续读本地 `/api/media/{id}`；Nginx `/oss/` 需已配置 |
| R2 → DATABASE | 新上传写库，返回 `/api/media/{id}`；旧 R2 直链继续经 Nginx 读 R2 |
| 删除旧介质配置 | 该介质的历史附件不可读；管理端计数用于提前发现；DIRECT 下旧 R2 直链会失效 |
| 迁移历史文件 | 不在本设计内；P1 单独任务，需逐条更新 `storage_mode` / `storage_key` |
| 多实例 | LOCAL 不可用；R2 / DATABASE 可用 |

---

## 10. 安全与校验

### 10.1 独立媒体域是什么

独立媒体域 = **在同一个 Nginx 上再配置一个 `server_name`**，例如：

```text
https://example.com/api/media/{id}      页面 / 管理端同域
https://media.example.com/oss/{key}     独立媒体域，指向同一 Nginx / R2
```

它不是另一台服务器，也不是另一套应用；只是给外链换一个 origin。好处：

- **Cookie 隔离**：`example.com` 的 Cookie 不会发送到 `media.example.com`，即使某个文件被 inline 渲染，XSS 也拿不到主站会话；
- **安全头隔离**：媒体域可以统一加 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: sandbox`，不影响页面；
- **缓存 / CDN 独立**：媒体流量可以单独限速、缓存、切 CDN；
- **迁移方便**：以后把 `media.example.com` 指向 R2 / CDN，不需要改页面域名。

代价：

- 多一个 DNS 记录和 TLS 证书；
- 外链是绝对地址（`https://media.example.com/...`），域名变更会影响历史链接；
- 如果前端用 JS `fetch` 媒体并需要读取内容，要配 CORS；`<img>` / 下载链接不需要。

**独立媒体域能做什么、不能做什么：**

| 能力 | 说明 |
| --- | --- |
| Cookie 隔离 | 主站 Cookie 不发送到媒体域，降低 XSS 后会话被窃取的风险 |
| 同源隔离 | 媒体域上的 HTML / JS 不在主站 origin 执行 |
| 独立安全头 | `nosniff`、`Content-Security-Policy: sandbox`、`Referrer-Policy` |
| 独立限流 / WAF | 可按媒体域单独配置 `limit_req`、Cloudflare Rate Limiting、WAF |
| 独立缓存 / CDN | 媒体流量与页面流量分开缓存、计费、迁移 |
| **不能防刷** | 知道 URL 仍然可以请求媒体域；公开 R2 仍可绕过媒体域直连源站 |
| **不能替代鉴权** | 公开附件本来就是匿名可读；私有附件需要签名 / 鉴权 |

**当前建议：采用独立媒体域。** 它不能单独解决「被刷」，但能把攻击面和主站隔离开，并为后续 Nginx / Cloudflare 限流提供独立入口。

同域部署的缓解措施（如果暂时不拆域）：

- 非图片强制 `Content-Disposition: attachment`；
- `X-Content-Type-Options: nosniff`；
- `/oss/` 统一加 `Content-Security-Policy: sandbox`；
- 禁止 SVG；
- 不建议长期把任意文件与后台放在同一 origin。

### 10.2 安全策略

- **MIME / 魔数**：不能只信 `Content-Type`；至少校验常见图片文件头；SVG 直接拒绝；未知类型回落 `application/octet-stream`。
- **内联策略**：只有图片 inline；HTML / XHTML / JS / CSS / XML / SVG 等强制 `attachment`，避免同源 XSS。
- **独立媒体域**：建议采用 `media.example.com`，与应用 / 管理端分开，减少 Cookie 与同源风险；Nginx 加 `X-Content-Type-Options: nosniff`。
- **文件名**：不参与存储路径；响应头文件名安全转义；`storage_key` 只由服务端生成。
- **路径穿越**：LOCAL 实现 `root.resolve(key).normalize()` 后必须 `startsWith(root)`。
- **凭据**：R2 access key / secret 只从环境变量或本地忽略文件读取；不写 `site_setting`、不写日志、不返回管理端。Nginx 代理公开 bucket 不需要 R2 凭据；私有 bucket 的 Nginx 签名需要单独管理凭据。
- **公开读**：`/api/media/{id}` 匿名可读，依赖 UUID 不可枚举；R2 公开 bucket 下知道 `/oss/{key}` 即可访问，知道 R2 公开域 + key 也可绕过 Nginx 直接访问。**Nginx 不是访问控制层**。改为私有 bucket 后，R2 源站不可直连，媒体域仍是匿名入口，但至少所有请求经过 Nginx / 媒体域。
- **Nginx**：`client_max_body_size 25m`（仅上传入口）；`/oss/` 只允许 GET / HEAD；`limit_req` / `limit_conn`；隐藏 R2 内部头；限制缓存目录权限；TLS。
- **封面**：只允许图片；后端校验，避免非图片作为封面。
- **上传配额 / 限流**：上传接口需要 Nginx `limit_req` + 应用侧配额；下载侧按 §10.3 处理。

### 10.3 公开 bucket 的滥用风险与防刷

**公开 R2 的风险模型：**

| 风险 | 说明 |
| --- | --- |
| 直连源站 | 知道 R2 公开域 + key 即可绕过 Nginx，Nginx 限流 / 缓存 / `valid_referers` 全部失效 |
| 热点盗链 | 文章图片被外站引用，流量与 R2 请求数算在你的账上 |
| 恶意刷量 | 脚本遍历 / 重复请求已知 key，制造 R2 Class B 操作与流量 |
| 枚举 | 随机 UUID 虽然难猜，但链接会出现在页面、日志、分享、爬虫缓存中 |
| 成本 | R2 无 egress 费，但请求数、操作数、Worker / WAF 仍可能计费；异常流量需要告警 |

**防刷分层：**

1. **源站层（最关键）**：R2 bucket 改**私有**，关闭 `r2.dev` 公开开发域，确保 R2 不可直接访问；
2. **入口层**：独立媒体域只暴露 Nginx（或 Nginx + 后端签名），所有请求经过统一入口；
3. **限流层**：Nginx `limit_req_zone` / `limit_conn_zone`，Cloudflare Rate Limiting / WAF；
4. **缓存层**：Nginx `proxy_cache`、`proxy_cache_lock`，减少回源；热点文件命中缓存后不消耗 R2 请求；
5. **签名层**：短时签名 URL / Nginx 侧签名，避免长期可复制的直链；公开内容可接受长缓存，但源站仍应私有；
6. **观察层**：R2 请求 / 流量 / 账单告警，Nginx access log 按 key / referer 统计；
7. **弱防护（只能提高门槛）**：`valid_referers` 防盗链（可伪造 Referer）、随机 key、`robots.txt`（对恶意爬虫无效）。

**关键结论：**

- 公开 bucket + Nginx 限流只能保护 Nginx 入口，**不能保护 R2 源站**；
- 独立媒体域解决的是 origin 隔离，**不是防刷**；
- 如果担心「被外部肆意乱刷」，应优先把 R2 改成私有，再在 Nginx / 媒体域做限流与缓存；
- 私有 bucket 下，Nginx 直连需要签名：OpenResty/Lua SigV4、Nginx `auth_request` + 后端预签名、或后端预签名 302，见 §7.3。

---

## 11. 代码结构建议

```text
domain/
  Attachment.java            + storageMode
  StorageMode.java           新增枚举
repository/
  AttachmentRepository.java  + countByStorageMode
service/
  AttachmentService.java     编排：校验 / 元数据 / 路由 / 流式 I/O
  storage/
    AttachmentStorage.java   SPI（流式）
    AttachmentStorageRouter.java
    StorageModeProvider.java
    LocalAttachmentStorage.java
    R2AttachmentStorage.java
    DatabaseAttachmentStorage.java
    StorageAdminService.java
  media/
    MediaUrlResolver.java    稳定 URL / 可选绝对地址
web/
  AdminMediaController.java  + storage 端点（或独立 Controller）
  MediaController.java       LOCAL / DB 流式；OSS 302
config/
  StorageProperties.java     绑定 max-bytes / local / r2 / database / url-mode
startup/
  StorageStartupValidator.java
```

- `DatabaseAttachmentStorage` 用 `JdbcTemplate`；不建 `AttachmentBlob` JPA 实体，避免大字段映射；
- `AttachmentService` 仍然是对外唯一业务入口；`NoteService` / `PostService` 继续只调用 `validateCover`；
- 禁止 Controller 直接注入 `AttachmentStorage`；
- `attachment_blob` 无审计字段，属于审计例外，需记录到 `backend/AGENTS.md`。

---

## 12. 测试计划

| 层级 | 用例 |
| --- | --- |
| 单元 | `StorageMode` 解析；router 重复 / 缺失模式；LOCAL 路径穿越；DATABASE 20 MB 边界；R2 `NoSuchKey` 转 404（mock `S3Client`）；`MediaUrlResolver` 稳定 / 直接模式 |
| 集成（Testcontainers PostgreSQL） | LOCAL 上传 → 读取 → 删除；切到 DATABASE → 上传 20 MB → 流式读取；切回 LOCAL 后旧 DATABASE 附件仍可读；`attachment.storage_mode` 回填；`attachment_blob` 外键 / 级联 |
| 任意文件 | 图片 inline；PDF / ZIP 强制 attachment；SVG 拒绝；伪装 MIME 的 HTML 不 inline；封面传非图片 400 |
| 权限 | `admin:media:storage` 未授权 403；切换非法模式 400；R2 未配置切换失败；启动对账通过 |
| 契约 | `/v3/api-docs` 新 DTO 通过 `OpenApiContractIntegrationTest`；`pnpm gen:api` 生成物入库 |
| Nginx | DIRECT：`/oss/{key}` 代理 R2（可选 MinIO / 测试 bucket）；稳定端点 302；缓存头、Range、Content-Disposition 透传；同域 / 独立媒体域 CSP / nosniff |
| 安全 / 防刷 | 私有 bucket 时 R2 公开域不可直连；预签名过期；Nginx `limit_req` / `limit_conn` 生效；伪造 `Content-Type` / SVG / 超限文件被拒；LOCAL 目录穿越被拒；HTML 强制下载 |
| 性能 | 20 MB DB 上传 / 下载时堆内存不随文件大小线性增长（流式）；并发上限观察；公开 / 私有 R2 冷热请求对比 |

---

## 13. 实施顺序

1. **V11 + 行级 `storage_mode` + 流式 SPI + LOCAL 回归**：行为不变，先保证旧附件回填与读取正常；把 10 MB 调到 20 MB，multipart 同步调整。
2. **任意文件策略 + 前端媒体库 / 编辑器**：图片 / 非图片分流，封面仅图片，强制下载策略。
3. **DATABASE 适配器 + `attachment_blob`**：`JdbcTemplate` 流式 20 MB，同事务写入、读取、删除；补集成测试。
4. **R2 安全模式定稿**：公开 bucket + Nginx 代理，或私有 bucket + 预签名 / OpenResty 签名；先做 mock 单测，再用 MinIO / R2 测试桶冒烟。**如果担心被刷，私有 bucket 在实现阶段就应落地，不要留到 P1。**
5. **R2 适配器 + Nginx `/oss/` + DIRECT**：稳定端点 302 作为兜底；`edge=backend` 作为无 Nginx 的开发回退；配置 `limit_req` / `proxy_cache` / 账单告警。
6. **存储管理接口 + V12 权限 + 管理端入口**：运行时切换、健康检查、计数、提示。
7. **文档收口**：更新 `content-model.md` §3.5、`tech-stack.md` 待定 #2、`backend/AGENTS.md` 的存储 / 审计例外、API 生成物。
8. **P1（可选）**：历史文件跨介质迁移、孤儿清理、附件回收站、Range、CDN、WAF 规则细化。

---

## 14. 已确认与待确认

### 14.1 已确认（2026-10-31）

| 项 | 结论 |
| --- | --- |
| 对象存储 | Cloudflare R2，S3 兼容适配器 |
| R2 bucket | **私有（方案 B 已实现）** |
| 切换 | 运行时切换 |
| 文件范围 | 任意文件 |
| DATABASE 上限 | 20 MB |
| OSS 访问 | **DIRECT**：`/oss/{key}` → 媒体域 Nginx；私有 bucket 由 Nginx `auth_request` 取预签名 URL 并代理（方案 B） |
| LOCAL / DB 访问 | `/api/media/{id}` → Nginx → 后端 |
| 媒体域 | **建议独立媒体域**（`media.example.com`）；当前同域为临时简化 |
| Range | 本地 / DB 首版不做 |

### 14.2 仍需确认

1. **独立媒体域**：建议采用；能隔离 Cookie / XSS / 安全头，并便于单独限流 / WAF / 缓存。
2. **限流与告警**：Nginx `limit_req` / `limit_conn` 阈值；Cloudflare WAF / Rate Limiting 是否启用；R2 用量 / 账单告警。
3. **20 MB 是否包含所有文件**：视频等大文件是否需要更高上限？若需要，Range / 流式 / 分片上传要另设计。
4. **数据库膨胀**：20 MB × 文件数的备份体积是否可接受？是否需要监控 / 配额？
5. **删除缓存**：Nginx 缓存 TTL / 主动 purge 策略；DIRECT 模式下旧直链会一直可用，直到对象删除 + 缓存过期。
6. **退役 R2 的策略**：DIRECT 下历史直链绑定媒体域 `/oss/`；如果未来要退役 R2，需要保留只读 bucket + `/oss/` 路由，或统一迁回稳定端点。

---

## 15. 实现记录

### 15.1 后端

| 文件 | 作用 |
| --- | --- |
| `V11__attachment_storage_modes.sql` | `attachment.storage_mode` 回填 + `attachment_blob` |
| `V12__add_storage_permission.sql` | `admin:media:storage` ACTION |
| `domain/StorageMode.java` | LOCAL / OSS / DATABASE |
| `service/AttachmentStorage.java` | 流式 SPI |
| `service/LocalAttachmentStorage.java` | 本地原子写入 / 流式读取 |
| `service/R2AttachmentStorage.java` | R2 上传 / 读取 / 删除 / 健康检查 |
| `service/DatabaseAttachmentStorage.java` | `JdbcTemplate` 流式 bytea |
| `service/AttachmentStorageRouter.java` | 写读路由 |
| `service/StorageModeProvider.java` | 全局模式来源 |
| `service/R2PresignService.java` | 预签名 URL |
| `service/AttachmentService.java` | 任意文件校验、元数据、流 / 302 解析 |
| `web/MediaController.java` | 稳定端点 / 本地 DB 流式 / OSS 302 |
| `web/InternalMediaController.java` | Nginx `auth_request` 内部签名 |
| `web/StorageAdminController.java` | 存储状态与运行时切换 |
| `startup/StorageStartupValidator.java` | 启动期介质校验 |

### 15.2 前端 / 运维

- 媒体库支持任意文件、图片缩略图 / 文件卡片、复制 URL、存储状态与切换；
- 编辑器按 MIME 插入 `![]()` 或 `[]()`；
- 封面前端与后端双重限制为图片；
- Nginx 示例：`deploy/nginx/media.conf.example`；
- `application.yml` / `application-local.yml.example` 已补 R2、20MB、内部令牌等配置。

### 15.3 验证状态

- `mvn -DskipTests package` 通过；
- `mvn -Dtest=FileTypeDetectorTest,MediaUrlResolverTest test` 通过；
- `pnpm typecheck` 通过；
- Testcontainers 集成测试需要 Docker；当前环境未提供 Docker，`ContentApiIntegrationTest` 的媒体用例未在本机执行。
- OpenAPI 生成物 `schema.d.ts` 已按新增 DTO 补全；后端可运行后建议再执行 `pnpm gen:api` 做一次契约对账。

---

## 16. 变更记录

| 日期 | 版本 | 内容 |
| --- | --- | --- |
| 2026-10-31 | v0.1 | 首版设计草案：三介质抽象、行级 `storage_mode`、`attachment_blob`、稳定 URL、切换语义、迁移与测试计划 |
| 2026-10-31 | v0.2 | 确认 R2 / 运行时切换 / 任意文件 / 20 MB / Nginx 代理；改为流式 SPI；补充任意文件策略、Nginx 配置、公开 bucket 边界与 URL 两种模式 |
| 2026-10-31 | v0.3 | 确认 R2 公开 bucket、DIRECT 外链、同域 Nginx、本地 / DB 不做 Range；补充 DIRECT 影响、同域安全边界与独立媒体域说明 |
| 2026-10-31 | v0.4 | 复评公开 bucket 滥用风险：公开源站可被直连刷量；独立媒体域提供 origin 隔离但不等同防刷；补充私有 R2 + 预签名 / Nginx 签名方案、限流缓存告警与推荐顺序 |
| 2026-10-31 | v0.5 | 复评 A/B/C 性价比：B（Nginx `auth_request` + 后端预签名代理）作为首选；A 降为最小回退；C 保留高流量方案；明确 B 的缓存 / 安全头 / 隐藏 R2 收益与每请求签名子请求成本 |
| 2026-10-31 | v1.0 | 实现 LOCAL / R2 / DATABASE 三介质、流式 SPI、任意文件策略、20MB DATABASE、运行时切换、Nginx auth_request 方案 B、内部签名接口、管理端媒体库 / 存储设置；补 Nginx 示例与测试 |
