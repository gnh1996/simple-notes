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

## 数据库迁移约定

- 建表 / 改表只走 Flyway：脚本放 `src/main/resources/db/migration`，命名 `V<版本>__<描述>.sql`
- JPA `ddl-auto` 固定为 `validate`（见 `application.yml`），禁止改为 `update` / `create` / `create-drop`；实体与表结构不一致时启动即失败，先补迁移
- **已执行脚本不可变**：某个版本只要在任一环境的数据库中执行过（本地 / 测试 / 生产），就禁止修改其内容、文件名或版本号；Flyway 校验和不匹配时应排查，不得用 `repair` 掩盖
- **回退只能前进**：撤销 / 修正已执行的迁移，必须新增更高版本脚本（如 `V<N+1>__revert_xxx.sql`）做补偿；禁止删除历史脚本，禁止用 `flyway clean` 清库重来（社区版无 undo）
- 仅当脚本尚未在任何数据库执行过（如尚未合并）时，才允许修改；合并后即视为已发布

## 查询约定

- 列表查询未显式指定排序规则时，默认按创建时间降序（`create_time DESC`）
- 需要其他排序的场景（如按更新时间、置顶加权）须在对应设计 / 接口契约中显式说明
