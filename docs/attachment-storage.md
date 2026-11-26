# 附件存储设计（本地 / R2 / 数据库）

> **状态：已实现（LOCAL / R2 / DATABASE 三介质、私有 R2 + Nginx `auth_request` 方案 B、任意文件、20MB、运行时切换）。**
> 范围：附件元数据与二进制分离、三介质抽象与切换、任意文件策略、DIRECT 访问路径、媒体域、R2 访问链路、滥用防护。
> 关联：`docs/content-model.md`（附件表与媒体接口）、`docs/tech-stack.md`（R2 选型与机密配置）、`backend/AGENTS.md`（数据模型 / 迁移 / 权限约定）。

## 0. 结论与本次决策

**可行。** 现有 `attachment` 元数据表、`AttachmentStorage` SPI 和 `/api/media/{id}` 都可以继续使用。需要调整的是「存储介质按行记录」、二进制改为流式 I/O，以及把 OSS 数据面交给同域 Nginx + **私有 R2 + `auth_request` 预签名代理**。

| 决策点 | 结论 | 设计影响 |
| --- | --- | --- |
| 对象存储 | **Cloudflare R2**，S3 兼容适配器 | 不引入第二套 OSS SDK；R2 / MinIO 共用实现 |
| R2 bucket | **私有（方案 B 已实现）** | 公开 bucket 可被外部直接刷；私有才能让媒体域 / Nginx 成为唯一入口（见 §10.3） |
| 媒体域 | **独立媒体域（建议采用）** | 隔离 Cookie / 同源 / XSS；便于单独限流、缓存、WAF；但它本身不防热点盗链（见 §10.1） |
| 切换方式 | **运行时切换** | 全局 `storage.mode` 存 `site_setting`；仍需行级 `storage_mode` |
| 文件范围 | **任意文件** | 不是「没区别」：需要文件策略、下载语义、编辑器插入方式、封面仅限图片 |
| DATABASE 上限 | **20 MB** | 调大 multipart 上限；二进制必须流式读写，不能整块 `byte[]` |
| OSS 访问 | **DIRECT 外链 + 媒体域 Nginx + auth_request 预签名** | OSS 文件 URL 是 `/oss/{key}`；Nginx 向后端取预签名 URL 并代理 R2，浏览器只看到媒体域 |

### 0.1 三条硬边界

- **任意文件 ≠ 没有约束**：图片 inline，其余强制 `Content-Disposition: attachment`；封面只接受图片；类型校验用魔数，不信任 MIME。
- **20MB 必须流式**：DB 适配器走 `JdbcTemplate` + `setBinaryStream` / `getBinaryStream`，不得整块 `byte[]`；multipart 上限同步 20MB / 25MB。
- **私有 bucket 才谈得上防刷**：公开 bucket 可被直连 R2 源站；独立媒体域只解决 origin 隔离，不解决刷量。防刷 = 私有源站 + 短时签名 + 限流 + 缓存 + 用量告警。

一句话：**元数据一张表，二进制三种介质，写路径看全局设置，读路径看行记录；防刷靠私有源站 + 签名 + 限流，独立媒体域负责隔离，不负责挡住刷量。**

## 1. 设计起点

已有的 `attachment` 元数据表、`AttachmentStorage` SPI 与 `/api/media/{id}` 全部保留。设计要解决的四个问题：

1. **切换语义**：不能用一个全局模式同时决定读写，否则切换后历史附件 404 → 全局模式决定写入，行级 `storage_mode` 决定读取；
2. **文件策略**：任意文件需要 MIME 之外的魔数校验、下载语义与前端插入方式；
3. **I/O 模型**：20MB 与 Nginx 代理要求流式读写，不能用 `byte[]`；
4. **访问链路**：OSS 走 Nginx 数据面，同时保持 URL 稳定与运行时切换兼容。

## 2. 非目标

- 不做浏览器直传 R2 / 预签名上传；
- 不做历史文件跨介质自动搬迁；
- 不做图片多尺寸、压缩、转码；
- 不做附件回收站（删除即删二进制）；
- 不做完整 Range / 断点续传（Nginx 代理 R2 天然支持 Range，本地 / DB 不承诺）；
- 不做桌面端本地附件库 / 离线队列；
- 不做多租户 bucket。

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
  └─ /oss/**              → 私有 R2（auth_request 预签名后代理 / 302）（proxy_cache）
```

**DIRECT 模式下：**

- `LOCAL` / `DATABASE`：`AttachmentResponse.url = /api/media/{id}`，Nginx 转发到后端；
- `OSS`：`AttachmentResponse.url = /oss/{storageKey}`，Nginx 先 `auth_request` 取后端预签名，再代理私有 R2，**不经过后端数据面**；
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
| Nginx | `/oss/**` 经 `auth_request` 代理私有 R2、`/api/media/**` 转发后端、缓存、Range、TLS | 业务权限、附件元数据 |

### 3.2 三种介质的取舍

| 维度 | LOCAL | R2（S3 兼容） | DATABASE |
| --- | --- | --- | --- |
| 适用场景 | 本地开发、单机部署 | 生产、多实例、Nginx/CDN 前置 | 小文件、无外部依赖、随库备份 |
| 多实例 | **不适合**（每实例磁盘不同） | 适合 | 适合（共享库） |
| 数据面 | Nginx → 后端 | **Nginx → R2（不经过后端）** | Nginx → 后端 |
| 备份 | 备份目录 / 卷 | bucket 版本 / 生命周期 | 随数据库 dump，库会变大 |
| 单文件上限 | 20 MB | 20 MB | **20 MB** |
| 主要风险 | 节点漂移、卷丢失 | 私有 bucket 的 Nginx / 签名配置 | 库膨胀、连接池与内存 |

结论：**LOCAL 仅用于开发 / 单机；生产优先 R2；DATABASE 作为无外部依赖的兜底。** 多实例部署禁止 LOCAL。

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

## 6. 系统设置与切换语义

### 6.1 设置键

复用现有 `site_setting` KV 表，新增键：

| 键 | 类型 | 默认 | 公开 | 允许值 |
| --- | --- | --- | --- | --- |
| `storage.mode` | STRING | `LOCAL` | 否 | `LOCAL` / `OSS` / `DATABASE` |

- 复用现有 `site_setting` KV 表与上游优先级（`site_setting.storage.mode` > `simple-notes.storage.mode` > `LOCAL`）；**当前实现不把该键放进 `SiteSettingCatalog`**，因此不会出现在站点设置表单，也不能通过 `PUT /api/admin/site/settings` 写入；读写由 `StorageModeProvider`（只读）与 `StorageAdminService`（切换）直接操作 `SiteSettingRepository`。
- `publicReadable = false`：不进入博客公开配置。

### 6.2 切换流程

```text
PUT /api/admin/media/storage  { "mode": "OSS" }
  │
  ├─ 校验 mode 合法
  ├─ router.forRead(mode).validate()      // 本地目录可写 / R2 headBucket / DB 可读
  ├─ 统计旧模式附件数量（提示：切换不迁移历史文件）
  ├─ 写入 site_setting.storage.mode（带 update_by / update_time）
  └─ 返回 { mode, availableModes, counts, health }
```

规则：

1. **切换只改写入目标**。已有附件继续按行上的 `storage_mode` 读取；
2. 切到 R2 前必须通过健康检查，避免写入后才发现凭据 / bucket 错误；
3. 从 R2 切走前提示「旧 R2 附件仍依赖 R2 配置与 Nginx `/oss/` 路由」；
4. 切换不做批量搬迁；需要搬迁时另立 P1 任务；
5. 当前接口只接收 `mode`，没有乐观锁 `version`。存储切换是低频运维操作，后并发覆盖风险由管理端单入口承担；如未来需要多管理员并发，再补 `version`。

### 6.3 启动校验

`StorageStartupValidator`（`ApplicationRunner`）在 Flyway / JPA 就绪后执行：

- `storage.mode` 无法解析 → **启动失败**；
- 当前模式对应的实现不存在 → **启动失败**；
- LOCAL：根目录不存在则创建，不可写 → **启动失败**；
- DATABASE：`attachment_blob` 表可访问 → 失败即启动失败；
- R2：配置缺失 → **启动失败**；网络 / bucket 暂不可达 → 记录 WARN，不阻塞启动（切换时仍会硬校验）。

## 7. 访问路径

### 7.1 URL 策略：DIRECT 为默认

`storage.url-mode = DIRECT`：上传响应直接给出可访问 URL，OSS 不经过后端。

```text
LOCAL / DATABASE:  {MEDIA_PUBLIC_BASE_URL}/api/media/{id}     （Nginx → 后端，流式）
OSS:               {MEDIA_PUBLIC_BASE_URL}/oss/{storageKey}   （Nginx → R2，不经过后端）
```

稳定端点 `GET /api/media/{id}` 保留：LOCAL / DB 直接返回，OSS 302 到 `/oss/{key}`。用途是兼容历史正文 / 封面链接、手写链接与调试，也支持切回 `STABLE`。

代价：DIRECT 的 URL 与介质绑定，旧直链依赖「旧介质 + Nginx 路由」继续存在；退役 R2 或删除 `/oss/` 会让旧直链失效。需要「切介质不坏历史链接」时改用 `STABLE`（恒为 `/api/media/{id}`，OSS 由后端 302，多一跳）。

### 7.2 媒体域 Nginx（方案 B）

完整示例见 `deploy/nginx/media.conf.example`。不变量：

- `/oss/` 不直接 `proxy_pass` 到 R2：先 `auth_request` 调后端内部签名接口，拿短期预签名 URL 再代理；
- 浏览器只看到媒体域，R2 endpoint / 签名不暴露；
- `proxy_cache_key` 忽略签名 query，缓存文件体；附件 key 不可变，删除依赖短 TTL / purge / 墓碑；
- 后端签名接口只做轻量校验与 HMAC，按请求执行是可接受的成本。

### 7.3 备选链路

- **A｜私有 R2 + 后端预签名 302**：最小回退。Nginx 不缓存文件体、不能给最终响应加安全头，适合低带宽 VPS；配合 `url-mode=STABLE` 时正文仍写 `/api/media/{id}`。
- **C｜私有 R2 + OpenResty/Lua SigV4**：签名全在 Nginx，运维成本最高，流量上来再评估。
- 公开 bucket 只适合「知道 URL 就能看」的非敏感图片，不能作为防刷方案。

### 7.4 对外接口

| 方法 / 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/media/{id}` | 匿名 | LOCAL / DB 流式返回；OSS 302 到 `/oss/{key}`（稳定 / 兜底） |
| `GET /oss/{key}` | 匿名 | OSS 直链；私有 bucket 由 Nginx `auth_request` 取预签名后代理 |
| `POST /api/admin/media` | `admin:media` | 上传（`multipart/form-data`，字段 `file`），任意文件 |
| `POST /api/desktop/media` | `desktop:media:upload` | 桌面端上传，复用 `AttachmentService.upload`（见 `docs/desktop-media.md`） |
| `GET /api/admin/media` | `admin:media` | 媒体库分页列表：`page` / `size`（默认 24，上限 100）/ `keyword`（原始文件名）；按 `create_time DESC` |
| `DELETE /api/admin/media/{id}` | `admin:media` | 软删元数据 + 清理二进制 |
| `GET /api/admin/media/storage` | `admin:media` | 当前模式、可用模式、各模式计数、健康状态（只暴露安全摘要） |
| `PUT /api/admin/media/storage` | `admin:media:storage` | 运行时切换新上传介质，body `{ "mode": "LOCAL\|OSS\|DATABASE" }` |

响应与 DTO 以 OpenAPI 为准；`AttachmentResponse.url` 规则见 §7.1。切换接口失败语义：非法模式 400，R2 未配置 409 / 400，权限不足 403。

### 7.5 存储管理

`GET /api/admin/media/storage`（`admin:media`）返回当前模式、可用模式、各模式计数与健康摘要；`PUT`（`admin:media:storage`）切换新上传介质。计数按 `storage_mode` 分组、只统计未删除附件；健康状态不返回 endpoint / bucket / 凭据。

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

## 9. 关键流程与失败处理

### 9.1 上传

```text
1. 校验：非空、大小（全局 20 MB + 当前模式上限）、文件策略、魔数、禁止 SVG
2. mode = StorageModeProvider.current()
3. storage = router.forRead(mode)   // 启动 / 切换时已做 validate；上传路径不重复健康检查
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
| **不能防刷** | 知道 URL 仍然可以请求媒体域；若 R2 bucket 为公开，还可绕过媒体域直连源站（当前私有 bucket 已消除该直连面） |
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
- **凭据**：R2 access key / secret 只从环境变量或本地忽略文件读取；不写 `site_setting`、不写日志、不返回管理端。当前私有 bucket 由后端持有 R2 凭据并通过 `auth_request` 签发短期预签名 URL；Nginx 不持有 R2 凭据。
- **公开读**：`/api/media/{id}` 匿名可读，依赖 UUID 不可枚举；当前私有 bucket 下 R2 源站不可直连，`/oss/{key}` 由媒体域 Nginx 代理，所有请求经过 Nginx / 媒体域；媒体域仍是匿名入口。**Nginx 不是访问控制层**。
- **Nginx**：`client_max_body_size 25m`（仅上传入口）；`/oss/` 只允许 GET / HEAD；`limit_req` / `limit_conn`；隐藏 R2 内部头；限制缓存目录权限；TLS。
- **封面**：只允许图片；后端校验，避免非图片作为封面。
- **上传配额 / 限流**：上传接口需要 Nginx `limit_req` + 应用侧配额；下载侧按 §10.3 处理。

### 10.3 公开 bucket 的滥用风险与防刷（历史评估；当前采用私有 bucket）

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

## 11. 实现约束与待定

实现约束（已落地，改动时不得破坏）：

- `AttachmentService` 是唯一业务入口；Controller 不得直接注入 `AttachmentStorage`。
- `DatabaseAttachmentStorage` 用 `JdbcTemplate` 流式读写，不建 `AttachmentBlob` JPA 实体；`attachment_blob` 无审计字段，属审计例外（见 `backend/AGENTS.md`）。
- 全局 `site_setting.storage.mode` 只决定**新上传**介质，行级 `attachment.storage_mode` 决定**读取**介质；两者不可合并。
- `storage_key` 与访问 URL 解耦；URL 规则只写在 `MediaUrlResolver`。

待定（未实现，非承诺）：

1. **独立媒体域**：建议 `media.example.com`；当前同域为临时简化，切换前评估 Cookie / CSP / 限流。
2. **限流与告警**：`limit_req` / `limit_conn` 阈值、Cloudflare WAF、R2 用量与账单告警。
3. **上限与 Range**：20MB 之外是否需要更大大文件；本地 / DB 首版不做 Range。
4. **数据库体积**：DB 介质的备份体积是否需要配额或监控。
5. **缓存与退役**：Nginx 缓存 TTL / purge；退役 R2 需保留只读 bucket 或统一迁回稳定端点。
6. **图片重编码**：当前按原样存储；是否统一转码 / 去 EXIF 待评估。
