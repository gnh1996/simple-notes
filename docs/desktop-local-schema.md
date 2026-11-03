# 桌面端本地库结构演进（SQLite Schema 迁移）设计

> **状态：v1.2 · 2026-11-02（S1 + S2 已实现：执行器 / 0001+0002 / 备份 / 降级检测 / 启动阻塞；追加 0003 本地图片附件表，见 `docs/desktop-media.md`；S3 协议握手待触发）**
> 范围：桌面端 SQLite 表结构的版本化与迁移机制、失败与降级处理、与后端 API 契约版本的关系
> 关联：`docs/desktop-sync.md`（本地数据模型 / 同步协议）、`docs/desktop-local-file.md`（本地文件表）、`docs/tech-stack.md`（选型）、`desktop/AGENTS.md`
> 非目标：down 迁移、多实例并发迁移、数据库加密、跨设备同步 schema、服务端 Flyway 变更

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 版本载体 | `PRAGMA user_version`（32 位整数，单调递增，从 1 开始） |
| 迁移单元 | 有序编号 `0001…`，支持「SQL 文件」与「Go 函数」两种形态 |
| 执行粒度 | **每条迁移一个事务**，DDL 与 `user_version` 写入同事务提交 |
| 兼容桥接 | `0001` 全量 `CREATE TABLE IF NOT EXISTS` + `0002` Go 函数补列，同时覆盖新库与老库 |
| 降级检测 | `user_version > latest` → **fail-fast**，不读不写，提示升级应用 |
| 迁移前备份 | `current < latest` 时先 `VACUUM INTO` 一份，失败则中止迁移 |
| 失败处理 | 该条回滚，`user_version` 停在上一版；应用阻塞并给出诊断，**不静默重建库** |
| 与服务端的关系 | 本地 schema 版本与 API 契约版本**完全解耦**；协议协商延后，先固化「只加不改」纪律 |
| 文档漂移 | 以代码为准对齐 `docs/desktop-sync.md` §4（见 §6.3） |

## 1. 背景

### 1.1 现状

`desktop/internal/store` 目前没有版本号，也没有迁移框架：

- `store.go:49` 用 `CREATE TABLE IF NOT EXISTS` 建 `app_kv` / `auth_session`；
- `sync.go:106` 的 `migrateSync()` 建同步相关表与索引；
- `sync.go:198` 的 `ensureColumn()` 用 `PRAGMA table_info` 探测后 `ALTER TABLE ADD COLUMN`，补齐历史库缺的 3 列；
- 每次启动都完整跑一遍上述逻辑。

这套做法在「只加表 / 只加列」阶段是成立的，也有大量先例。问题不在现在，而在**第一次破坏性变更**。

### 1.2 现状的失效点

| 变更类型 | 现状能否表达 | 说明 |
| --- | --- | --- |
| 新增表 | ✅ | `CREATE TABLE IF NOT EXISTS` |
| 新增索引 | ✅ | `CREATE INDEX IF NOT EXISTS` |
| 新增带默认值的列 | ✅ | `ensureColumn` |
| 改列类型 / 改约束 | ❌ | SQLite 不支持，需要重建表 |
| 删除列 | ❌ | 同上；且老库残留列会让「文档 schema」与「实际 schema」永久漂移 |
| 重命名表 / 列 | ❌ | 需要显式 `ALTER TABLE RENAME`，重复执行会失败 |
| 数据回填 / 拆分表 | ❌ | 无法判断「是否已执行过」，重复执行会重复写入 |
| 判断库是否来自更高版本 | ❌ | 没有版本号，降级后无法拒绝启动 |

结论：**缺少的不是 DDL 能力，而是「当前处于哪一版」这个事实**。一旦有版本号，其余都可以按序执行。

### 1.3 客户端特有的约束

服务端的迁移经验不能直接照搬，桌面端多出三件事：

1. **用户可能跨版本升级**：从 v1.0 直接升到 v1.6，迁移必须是从任意旧版本到最新版本的链，不能只写「上一版 → 这一版」。
2. **用户可能降级**：装回旧版本后，库里的结构比应用认识的新。必须拒绝启动，而不是按旧 schema 硬读。
3. **迁移失败没有 DBA 兜底**：用户机器上的库没有备份、没有运维。失败必须可诊断、可恢复、可重试。

## 2. 目标与非目标

### 2.1 目标

1. 任意旧版本库（含当前无版本号的老库）都能一次启动迁移到最新结构，数据不丢。
2. 迁移过程崩溃或失败后，库处于**确定的中间版本**，不是半成品。
3. 版本高于应用支持范围时明确拒绝，而不是静默错乱。
4. 破坏性变更前有可回退的物理备份。
5. 迁移逻辑可单测、可 review，不依赖运行时探测的隐式行为。
6. 与后端版本约定有明确边界，避免「服务端升级导致桌面端本地库要跟着改」的误解。

### 2.2 非目标

- 不支持 down 迁移（客户端只前进；回退靠备份文件）；
- 不支持多进程 / 多实例同时迁移（桌面端单实例，WAL + `busy_timeout` 已足够）；
- 不引入迁移框架依赖（`golang-migrate` / `goose` 作为备选，见 §4.5）；
- 不加密数据库、不做 schema 版本上传或服务端校验；
- 不处理服务端 Flyway 的版本（两套库互不感知）。

## 3. 版本模型

### 3.1 四个版本轴，互不推导

| 轴 | 载体 | 权威方 | 取值范围 | 用途 |
| --- | --- | --- | --- | --- |
| 本地 schema 版本 | `PRAGMA user_version` | 桌面端 | 1, 2, 3… | 决定启动时执行哪些迁移 |
| 桌面端应用版本 | `system.Version`（构建注入） | 桌面端 | 语义化版本 | 展示、诊断、日志 |
| API 契约版本 | OpenAPI `info.version`（当前 `"v1"`） | 后端 | 静态标签 | 生成客户端类型、判断契约代际 |
| 内容修订号 | `note.version` | 后端 | 单调递增整数 | 同步 CAS、冲突判定 |

关键约束：

- **本地 schema 版本与服务端任何版本无关**，不随服务端发布变化，也不上传给服务端；
- **本地 schema 版本与应用版本无关**：不要求 `v1.2.0` 对应 schema `2`；一次发版可以包含 0 条或多条迁移；
- `note.version` 是**行级**修订号，禁止在任何新表 / 新列里用 `schema_version` 之类的名字，避免与库版本混淆。

### 3.2 本地 schema 版本规则

1. 从 `1` 开始，严格递增，**连续无空洞**；
2. 一旦随版本发布（进入任何用户的库），该编号的迁移内容**永久不可修改**；修正只能追加更高编号；
3. 永远只在末尾追加，不插入中间版本；
4. `latest = migrations[len-1].version`，不单独维护常量；
5. 注册表在启动时校验：非空、从 1 开始、连续、无重复、文件名编号与结构体编号一致。

第 5 条是低成本的「防手滑」：编号写错、文件漏提交、复制粘贴改漏，都会在启动时直接失败，而不是在用户机器上跑到一半才暴露。

### 3.3 为什么本地 schema 不跟服务端版本走

三种常见误解，逐条否掉：

| 误解 | 为什么不对 |
| --- | --- |
| 「服务端升级了，桌面端本地库也要升版本」 | 服务端改的是 PostgreSQL 表；桌面端只消费 JSON。只有**响应字段变化**才可能触发本地加列，而那是 API 契约的事 |
| 「本地库版本要报给服务端做兼容判断」 | 服务端不需要知道客户端存了什么。上报只会制造耦合，且暴露实现细节 |
| 「本地 schema 版本可以等于 API 契约版本」 | 两者节奏完全不同：本地可能一个版本加 3 列，也可能连续 5 个版本不动；契约版本是代际标签，不是计数器 |

**唯一正确的单向依赖**是：API 响应新增字段 → 桌面端决定是否落本地列 → 若落，则新增一条本地迁移。方向永远是从契约到本地，不可逆。

## 4. 迁移执行器

### 4.1 数据结构

```go
// desktop/internal/store/migrate.go
type migration struct {
    version int
    name    string
    file    string                    // 与 fn 二选一：embed 的 SQL 文件
    fn      func(*sql.Tx) error       // 与 file 二选一：条件性 DDL / 数据回填
    rebuild bool                      // 需要重建表：执行器负责开关外键与事后校验
}

var migrations = []migration{
    {version: 1, name: "init", file: "0001_init.sql"},
    {version: 2, name: "align_legacy_columns", fn: alignLegacyColumns},
}

//go:embed migrations/*.sql
var migrationFS embed.FS
```

- `file`：常规 DDL，可含多条语句（`modernc.org/sqlite` 的 `Exec` 支持多语句，现有代码已在用）；
- `fn`：只有「无法用纯 SQL 表达」时才用，典型是 `ensureColumn` 这类条件性 DDL，以及需要逐行计算的数据回填；
- `fn` 必须幂等（失败修复后可重跑），SQL 迁移不需要。

### 4.2 执行流程

```go
func (s *Store) migrate() error {
    current, err := s.userVersion()
    if err != nil {
        return err
    }
    latest := migrations[len(migrations)-1].version
    if current > latest {
        return fmt.Errorf("本地数据库版本 %d 高于当前应用支持的 %d，请升级应用", current, latest)
    }
    if current == latest {
        return nil
    }
    if err := s.backupBeforeMigrate(current); err != nil {
        return fmt.Errorf("迁移前备份失败: %w", err)
    }
    for _, m := range migrations {
        if m.version <= current {
            continue
        }
        if err := s.applyMigration(m); err != nil {
            return fmt.Errorf("迁移 %04d_%s 失败: %w", m.version, m.name, err)
        }
    }
    return nil
}

func (s *Store) applyMigration(m migration) error {
    tx, err := s.db.Begin()
    if err != nil {
        return err
    }
    defer tx.Rollback()

    if m.fn != nil {
        if err := m.fn(tx); err != nil {
            return err
        }
    } else if _, err := tx.Exec(readMigrationSQL(m.file)); err != nil {
        return err
    }

    // PRAGMA 不支持绑定参数；m.version 是代码内常量，无注入风险。
    if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", m.version)); err != nil {
        return err
    }
    return tx.Commit()
}
```

`PRAGMA user_version` 的赋值**参与事务**：SQLite 官方论坛已确认，事务内设置后 `ROLLBACK` 会恢复到原值。因此「DDL + 版本号」天然原子，不需要额外的 dirty 标记。

### 4.3 为什么每条迁移单独一个事务

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 全部迁移一个大事务 | 整体原子 | 一条失败全部回滚，无法定位；长事务占用 WAL；重跑成本高 |
| **每条一个事务（采纳）** | 失败停在确定版本；已成功的不用重跑；日志可定位 | 需要保证每条迁移自身幂等/可续 |

采用「每条一个事务」的前提是：迁移链**可以从任意中间版本继续**。这正是版本号带来的能力。

### 4.4 为什么不用 `schema_migration` 表

| 方案 | 成本 | 收益 |
| --- | --- | --- |
| **A. `PRAGMA user_version`（采纳）** | 0 张表 | 够用：单机、单进程、无并发迁移 |
| B. `schema_migration(version, name, applied_at, checksum, dirty)` | 1 张表 + 读写逻辑 | 可查历史、可校验脚本是否被篡改、可标记 dirty |

选 A 的理由：

- 每条迁移一个事务后，**dirty 状态不存在**（崩溃时事务要么全提交要么全回滚）；
- 脚本篡改检测对客户端意义不大——应用是整体签名分发的，迁移文件不可能被单独替换；
- 历史记录对排障有用，但 `user_version` + 应用日志已经足够回答「卡在哪一版」。

**切换触发条件**：出现「需要按迁移记录做数据修复」或「需要区分同一版本的不同执行结果」时，再升级到 B，届时 `user_version` 可直接迁移为 `schema_migration` 的第一行。

### 4.5 为什么暂不引入迁移框架

`golang-migrate` 的 `database/sqlite` 驱动底层就是 `modernc.org/sqlite`（纯 Go，符合「无 CGO」约束），`goose` 也支持 `database/sql` + SQLite。它们能提供 up/down、status、CLI。

不引入的理由：

- 桌面端不需要 down（回退靠备份）、不需要 CLI、不需要多数据库方言；
- 现有代码风格是「DDL 写在 Go 里」，引入框架要额外维护 `migrations/` 目录与版本表，收益低于成本；
- 手写执行器约 100 行，行为完全可控，且能直接复用 `ensureColumn` 做老库桥接。

**切换触发条件**：迁移条目超过 ~15 条、需要 down、或需要 lint（破坏性变更检测）时，再评估 `golang-migrate`。届时把现有 SQL 文件原样迁出即可，版本号语义不变。

### 4.6 迁移文件组织

```
desktop/internal/store/
├── migrate.go
├── migrations/
│   ├── 0001_init.sql
│   └── 0003_add_xxx.sql
└── store.go
```

- 文件名 = `%04d_<snake_case>.sql`，编号必须与 `migrations` 注册表一致；
- 一个文件可以包含多条语句，按书写顺序执行；
- 不写 `BEGIN` / `COMMIT`（执行器已包事务）；
- 文件顶部用注释写明「改了什么、为什么、是否破坏性」。

### 4.7 迁移编写规则

1. **只追加**：已发布的迁移不修改、不删除、不重命名、不调整编号；
2. **DDL 与数据回填分开**：加列一条、回填一条，便于单独重试与定位；
3. **删除列分两步**：先停止读写（应用层不再 `SELECT` 该列），下一个版本再物理删除；
4. **重建表的标准流程**（SQLite 无 `ALTER COLUMN`）：
   ```
   PRAGMA foreign_keys=OFF;          -- 必须在事务外，事务内是 no-op
   BEGIN;
   CREATE TABLE t_new (...);
   INSERT INTO t_new SELECT ... FROM t;
   DROP TABLE t;
   ALTER TABLE t_new RENAME TO t;
   CREATE INDEX ...;
   PRAGMA user_version = N;
   COMMIT;
   PRAGMA foreign_key_check;         -- 失败则阻塞启动并提示
   PRAGMA foreign_keys=ON;
   ```
   需要重建的迁移在注册表标 `rebuild: true`，执行器负责开关外键与事后检查；
5. **禁止**在迁移里做网络请求、读用户文件、依赖当前时间以外的外部状态；
6. **时间列统一 epoch 毫秒 `INTEGER`**（见 §6.2）。

当前库里没有任何外键，`rebuild` 路径暂时用不上；先把规则写死，避免第一次重建时临时发挥。

### 4.8 迁移写错了怎么办

「已发布不可修改」需要补一条精确判据，否则遇到「迁移在用户机器上失败」时会卡死。

失败的那条迁移是**回滚**的，所以它的效果没有留在任何库上（`user_version` 也停在上一版）。据此分三种情况：

| 情况 | 处理 |
| --- | --- |
| 迁移**从未在任何库成功执行** | 可以直接改同一编号的文件，随修复版本一起发。因为没有库到达过这个版本，不存在「两个库同版本、结构不同」 |
| 迁移**已在部分库成功**，但对另一部分库失败 | **不能改结果**，只能把它改得**更宽容**（例如改成 `ensureColumn` 式的幂等写法），或追加修复迁移 |
| 无法判断是否成功过 | 按「已成功」处理：追加修复迁移 |

判据可以压缩成一句：**编辑已发布迁移，只有在「对已成功执行过的库，产生的最终结构不变」时才安全。**

- 把 `ALTER TABLE ADD COLUMN` 改成「先探测再添加」→ 最终结构不变，安全；
- 把列类型从 `TEXT` 改成 `INTEGER`，或调整默认值 → 最终结构变了，不安全。

预发布阶段用户量小、可联系，第一条路径（直接改）是常态；正式发版后应默认走「追加修复迁移」。

## 5. 安全网

### 5.1 迁移前备份

```go
func (s *Store) backupBeforeMigrate(from int) error {
    dst := fmt.Sprintf("%s.bak.v%d", s.path, from)
    if _, err := s.db.Exec(`VACUUM INTO ?`, dst); err != nil {
        return err
    }
    return nil
}
```

- 只在 `current < latest` 且库中已有用户表时执行（全新安装没有可丢的数据，不复制空库）；
- `VACUUM INTO` 产出的是**不含 WAL 的完整单文件**，比直接复制 `.db` + `-wal` + `-shm` 可靠；它不能在事务内执行，所以放在迁移循环之前；
- 命名带来源版本，排障时能判断「这份备份对应哪一版」；
- 保留策略：**只保留最近一份**，新备份成功后删除更早的 `.bak.v*`；备份失败则中止迁移（不允许无备份做破坏性变更）；
- 数据库体积为纯文本笔记量级，备份成本可忽略，因此不区分「破坏性 / 非破坏性」，一律备份。

### 5.2 降级检测

`user_version > latest` 时：

- 不执行任何迁移、不打开业务读写；
- 返回可识别的错误类型（如 `ErrSchemaTooNew`），由 UI 弹出明确文案：
  > 本地数据库由更新版本的 Simple Notes 创建（v3，当前应用支持 v2）。请升级应用，或从 `desktop.db.bak.v*` 恢复后重试。
- **禁止**尝试「按旧 schema 只读」——字段缺失或语义变化会静默产出错误数据。

### 5.3 失败处理

单条迁移失败时，该条事务回滚，`user_version` 停在上一版，库处于**确定的旧版本**。此时：

1. 应用不进入主界面，弹出阻塞错误：失败的迁移编号、错误信息、数据库路径、备份路径；
2. 提供「打开数据目录」「复制诊断信息」按钮；
3. **不自动重建库、不自动清空数据**（会丢用户内容）；
4. 用户可选择用备份文件覆盖后回退到旧版本应用；或等待修复版本——修复版本会从上一个成功版本继续执行。

### 5.4 崩溃恢复

- 事务提交前崩溃：SQLite 回滚，`user_version` 不变，下次启动重跑该条；
- 事务提交后崩溃：该条已生效，下次启动从下一条继续；
- 重建表迁移在 `COMMIT` 后、`foreign_key_check` 前崩溃：结构已正确，仅缺一次校验；下次启动若检测到「上次最后执行的是 rebuild」，补跑一次 `foreign_key_check`（用一个 `ui_state` 键记录，避免每次启动全库扫描）。

## 6. 表结构约定（桌面端本地库）

### 6.1 命名与类型

| 项 | 约定 |
| --- | --- |
| 表名 | 小写下划线；同步域表带 `_local` 后缀（`note_local` / `folder_local`），全局表不带 |
| 主键 | 同步域表 `(scope_id, id)` 复合主键；全局表单列主键 |
| 时间 | `INTEGER`，epoch 毫秒；列名 `*_at` 或 `create_time` / `update_time` |
| 布尔 | `INTEGER` 0/1，列名用肯定语义（`dirty` / `deleted` / `pinned`），默认 0 |
| JSON | `TEXT`，列名 `*_json`，默认 `'[]'` 或 `'{}'`；读写统一走 Go 侧 marshal |
| 索引 | `idx_<table>_<用途>`，如 `idx_note_local_list` |
| 作用域 | 除 `app_kv` / `auth_session` / `recent_file` / `ui_state` 外，所有业务表必须带 `scope_id` |

### 6.2 已知不一致（不在本次统一）

| 位置 | 现状 | 处理 |
| --- | --- | --- |
| `app_kv.update_time` | `TEXT` + `datetime('now')`（UTC 字符串） | 保留。无业务查询依赖，迁移成本大于收益；**新表一律用 epoch 毫秒** |
| `auth_session.update_time` | 同上 | 同上 |
| `note_local.has_unpublished_changes` | 可空，无默认值 | 保留。NULL 表示「服务端未返回」，与 `false` 语义不同，属于有意设计 |

### 6.3 文档与实现的漂移（随本次一并修正）

以代码为准，`docs/desktop-sync.md` §4 需要修正：

| 表 | 漂移 |
| --- | --- |
| `note_local` | 文档缺 `remote_deleted`、`base_hash`、`synced_at`；`remote_version` / `base_version` 文档可空、代码 `NOT NULL DEFAULT 0`；索引名不一致（文档 `idx_note_local_scope_updated` + `idx_note_local_dirty`，代码 `idx_note_local_list`） |
| `folder_local` | 文档有 `deleted` 列，代码**物理删除、无此列**；文档缺 `create_time` |
| `sync_scope` | 文档缺 `nickname`；文档 `server_url` / `user_id` / `username` 可空、代码 `NOT NULL` |
| `outbox` | 一致 |

漂移本身不致命，但会让「设计文档 → 迁移脚本」的转换失去依据。S1 落地时同步更新 §4。

## 7. 落地切分（v1）

### 7.1 迁移清单

**`0001_init.sql`**：当前代码的全部建表语句（`app_kv`、`auth_session`、`sync_scope`、`note_local`、`folder_local`、`outbox`、`recent_file`、`ui_state` + 索引），全部 `IF NOT EXISTS`。

**`0002_align_legacy_columns`（Go 函数）**：把现有 `ensureColumn` 的三次调用原样搬进来：

```go
func alignLegacyColumns(tx *sql.Tx) error {
    for _, c := range []struct{ table, column, def string }{
        {"note_local", "remote_deleted", "INTEGER NOT NULL DEFAULT 0"},
        {"folder_local", "dirty", "INTEGER NOT NULL DEFAULT 0"},
        {"folder_local", "create_time", "INTEGER NOT NULL DEFAULT 0"},
    } {
        if err := ensureColumnTx(tx, c.table, c.column, c.def); err != nil {
            return err
        }
    }
    return nil
}
```

`ensureColumnTx` 是现有 `ensureColumn` 的事务版（`PRAGMA table_info` 改为 `tx.Query`）。

**`0003_local_media.sql`**：新增 `local_attachment` 表与 `create_time` 索引，承载桌面端本地笔记图片的元数据；二进制放在 `desktop.db` 同级的 `media/local/`，不参与同步（`docs/desktop-media.md`）。

### 7.2 路径推演

| 起始状态 | `user_version` | 执行 | 结果 |
| --- | --- | --- | --- |
| 全新安装 | 0 | `0001` 建表 → `0002` 补列（空操作）→ `0003` 建本地附件表 | 3 |
| 现有老库（代码建的表，缺 3 列） | 0 | `0001` 全部跳过 → `0002` 补 3 列 → `0003` 建表 | 3 |
| 已有老库但 `recent_file` 缺失 | 0 | `0001` 补建缺失表 → `0002` 补列 → `0003` 建表 | 3 |
| 已迁移到最新 | 3 | 无 | 3 |
| 降级后的库 | 4 | 拒绝启动 | 错误 |

老库不需要「baseline 特判」：`IF NOT EXISTS` 让 `0001` 对新旧库都成立，`0002` 的幂等补列把差异抹平。**这是本设计能平滑接管现有库的关键**，也是为什么 `0002` 必须是 Go 函数而不是纯 SQL。

### 7.3 代码改动清单

| 文件 | 改动 |
| --- | --- |
| `store/store.go` | 删除内联 DDL，`OpenAt` 改为「打开 → PRAGMA → 构造 Store → `migrate()`」 |
| `store/sync.go` | 删除 `migrateSync()`；`ensureColumn` 改造成事务版并移入 `migrate.go` |
| `store/migrate.go` | 新增：注册表、执行器、备份、降级检测 |
| `store/migrations/0001_init.sql` | 新增：全量建表 |
| `store/migrate_test.go` | 新增：§9 的用例 |
| `main.go` + `startup_error_*.go` | 识别 `ErrSchemaTooNew` 与迁移失败：写 `desktop-error.log`，Windows 弹原生 MessageBox，不进入主界面 |

## 8. 与服务端的版本约定（评估）

### 8.1 现状

- 后端没有「服务端版本」端点：无 `/api/version`，无 Actuator 暴露；
- OpenAPI `info.version` 固定为 `"v1"`（`OpenApiConfig.java:28`），是**契约代际标签**，不是构建版本；
- 桌面端 `system.Version` 只是本地展示用，不上报；
- 桌面端允许用户填写任意服务器地址（`docs/desktop-sync.md` §7），因此**服务端与桌面端的版本天然可能不一致**；
- 当前处于预发布阶段，`AGENTS.md` 明确「接口与数据结构允许破坏性调整，不承担兼容旧客户端的成本」。

### 8.2 结论先行

**现阶段不引入协议版本协商，但把「只加不改」写成纪律，并预留触发条件。**

理由：协商机制的成本是真实存在的（端点 + 过滤器 + 常量 + 双端发版纪律），而当前唯一的服务端与桌面端由同一次发布控制，收益为零。等到真正需要跨版本共存时再实现，设计已经在这里，落地约半天。

### 8.3 变更类型 × 影响矩阵

| 变更 | 影响本地 schema | 影响协议 | 处理方式 |
| --- | --- | --- | --- |
| 服务端响应**新增**字段 | 否 | 否（向后兼容） | 桌面端忽略；需要落库时再新增本地迁移 |
| 服务端响应**删除**字段 | 否 | **是** | 视为破坏性：提升协议号并强制升级（见 §8.9） |
| 服务端响应**改语义**（如 `version` 含义变化） | 否 | **是** | 同上 |
| 服务端请求**新增必填**字段 | 否 | **是** | 同上 |
| 服务端新增端点 | 否 | 否 | 桌面端按需接入 |
| 桌面端新增本地列 | **是** | 否 | 新增迁移 |
| 桌面端改同步算法 | 可能 | 否 | 新增迁移 + 必要时全量重算 |
| 内容模型加字段（如标签结构化） | **是** | **是** | 两端各自演进：契约只加字段，本地加列 |

矩阵的核心结论：**「协议破坏」与「本地 schema 变化」是两个独立事件**，只是有时同时发生。不能用其中一个的版本号去推断另一个。

### 8.4 三个方案评估

| 方案 | 做法 | 成本 | 收益 | 风险 |
| --- | --- | --- | --- | --- |
| **A. 不做（现状 + 纪律）** | 依赖「只加不改」+ 同步发版 | 0 | 预发布阶段够用 | 服务端破坏性变更时桌面端静默错（Go `json.Unmarshal` 忽略未知字段、缺失字段取零值） |
| **B. 请求头 + 426** | 桌面端带 `X-Desktop-Protocol`，服务端 `/api/desktop/**` 过滤器校验，低于最低值返回 `426 Upgrade Required` | 后端 1 个 Filter + 1 个常量；桌面端 1 行 | 强制升级，错误明确 | 需要双端同时改；协议号维护纪律 |
| **C. 能力发现端点** | `GET /api/desktop/meta` 返回 `{protocol, minProtocol, serverVersion}`，桌面端登录后探测 | 后端 1 个端点；桌面端 1 次请求 + 缓存 | 可展示、可降级、可诊断 | 多一次请求；仍需 B 才能防绕过 |

**推荐：A 现在，B + C 作为触发后的组合方案**（C 负责「告知」，B 负责「强制」）。

### 8.5 A 阶段必须固化的三条纪律

1. **`/api/desktop/**` 只加不改**：新增字段可以；删除、改名、改语义、加必填请求字段一律**提升协议号并强制客户端升级**，不新增路径版本段（理由见 §8.9）。破坏性变更必须同步更新 `docs/desktop-sync.md` §5 契约表。
2. **桌面端解析必须容忍缺失字段**：`json.Unmarshal` 之后显式判空，禁止假设「服务端一定会给」。当前 `syncsvc` 已有部分兜底，需在 code review 清单里固化。
3. **未知端点视为「服务端过旧」**：桌面端对「路径本身必须存在」的请求（manifest / 文件夹列表）使用 `api.GetEndpoint`；收到 `404` / `405` 时返回 `api.ErrServerTooOld`，UI 提示「服务端版本较低，请升级服务端」，而不是泛化的「请求失败」。已随 S2 落地。

### 8.6 触发条件

满足任一条件，立即实施 §8.7：

1. 第一次出现「必须服务端与桌面端同时升级」的变更（如 `note.version` 语义变化、manifest 游标语义变化）；
2. 桌面端第一次交付给「服务端版本不由我们控制」的用户（自托管分发）；
3. 服务端开始保留旧版接口（`v1` / `v2` 并存）。

### 8.7 触发后的最小设计（备查）

```
GET /api/desktop/meta        （认证后，权限 desktop:note）
→ 200 { "protocol": 2, "minProtocol": 1, "serverVersion": "1.6.0" }
```

- 桌面端常量 `desktopProtocol = 2`，登录成功后拉一次，缓存到 scope 行（或内存）；
- 判定：
  - `desktopProtocol < minProtocol` → 阻塞，提示升级桌面端；
  - `desktopProtocol > protocol` → 服务端较旧，提示部分功能不可用（或按功能降级）；
  - 其余 → 正常；
- 服务端侧仍保留 `X-Desktop-Protocol` 过滤器作为强制手段（防止客户端跳过 meta 调用）；
- 协议号只描述 `/api/desktop/**`，与 OpenAPI 的 `v1` 标签、本地 `user_version` 均无关。

### 8.8 明确不做的事

- 不把 `user_version` 放进任何请求头或请求体；
- 不让服务端校验客户端本地 schema；
- 不用服务端 `note.version` 或 OpenAPI `v1` 推导本地迁移编号；
- 不为「服务端升级」自动触发本地重建或清缓存。

### 8.9 为什么不用路径版本（`/api/desktop/v2/**`）

先澄清一个容易混的点：OpenAPI 里的 `info.version = "v1"` 是**契约元信息**，不是路径段；现有接口是 `/api/desktop/notes`，**路径里没有版本号**。引入 `/v2` 等于新增一套与现有「端路径域」并行的维度，不是对现状的自然延伸。

| 维度 | 路径版本 | 协议号（推荐） |
| --- | --- | --- |
| 表达方式 | `/api/desktop/v2/notes` | `X-Desktop-Protocol: 2` |
| 同时支持两代客户端 | 天然支持，两套 controller 并存 | 需服务层分支；通常直接淘汰旧版 |
| Controller 改动 | 复制控制器，或 `@RequestMapping` 多路径 + 分支 | 不动 |
| OpenAPI / 生成类型 | 两套 operation，客户端要选 | 一套 |
| 与 `ClientType.fromPath` | 兼容（前缀匹配 `/api/desktop/`） | 兼容 |
| 与 `ApiPathValidator` | 兼容（仍在 `/api/` 下） | 兼容 |
| 可见性 / 排障 | 日志、网关、缓存都直观 | 需看请求头 |
| 适用前提 | **必须长期并存两代客户端** | 可以强制升级 |

结论：桌面端是我们自己分发、可强制升级的客户端，不存在「旧客户端必须继续工作」的诉求，因此**默认不加路径版本**。路径版本只在以下情况启用：

1. 出现第三方客户端，无法强制升级；
2. 需要灰度：新旧桌面端在一段时间内并存。

届时也优先新增 `/api/desktop/v2/**`，而不是全局 `/api/v2/**`——后者不匹配任何 `ClientType.pathPrefix()`，`fromPath` 会返回 `null`，等于绕过「会话端一致性」校验（`SessionJwtFilter`），需要额外补规则。

## 9. 测试策略

| 用例 | 方式 | 断言 |
| --- | --- | --- |
| 注册表校验 | 单测 | 编号连续、无重复、`file` 存在、`file`/`fn` 二选一 |
| 空库 → 最新 | 单测（临时文件） | `user_version == latest`，所有表存在 |
| 老库 → 最新 | 单测：用 `migrate_test.go` 的 `legacySchema` 复现旧结构 + 写入一条 note，再 `OpenAt` | 3 个补列存在、note 数据未丢、`user_version == latest` |
| 重复迁移 | 单测：连续 `migrate()` 两次 | 第二次无操作、不报错 |
| 降级拒绝 | 单测：手工设 `user_version = latest+1` | 返回 `ErrSchemaTooNew`，不修改库 |
| 失败回滚 | 单测：注入一条必失败的迁移 | `user_version` 停在上一版，前一条数据仍在 |
| 备份生成 | 单测：`current < latest` | `.bak.v<from>` 存在且可独立打开 |
| 升级后业务回归 | 手工 | 登录、列表、打开、保存、冲突、离线新建全链路 |

历史 schema 用 **SQL 脚本复现**而不是提交二进制库文件：老 DDL 很短、可 review、可 diff。等正式发版后，再补一份真实老版本的二进制 fixture 做最终兜底。

## 10. 里程碑

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| S1 | 执行器 + `0001` / `0002` + 注册表校验 + 单测 + 文档 §4 对齐 | ✅ 2026-11-01 |
| S2 | 备份、降级检测、失败阻塞 UI、`ErrServerTooOld` 映射 | ✅ 2026-11-01 |
| S3 | 协议握手（`/api/desktop/meta` + `X-Desktop-Protocol`） | 待触发（§8.6） |

S1 完成后，`ensureColumn` 的隐式行为被收进 `0002`，后续所有变更都走「追加编号」，不再有例外。

## 11. 已知限制

- 不支持 down 迁移；回退只能靠 `.bak` 备份 + 旧版本应用；
- 重建大表时启动会变慢（当前数据量为文本笔记，可忽略；若未来引入全文索引需重新评估）；
- 备份只保留最近一份，连续升级两次后无法回到更早版本；
- 不支持多实例同时启动迁移（单实例假设；若未来支持多窗口进程，需要加文件锁）；
- 迁移失败后的恢复依赖用户手动操作备份文件，暂不提供自动恢复（避免掩盖 bug 与静默丢数据）。

## 12. 变更记录

| 日期 | 版本 | 说明 |
| --- | --- | --- |
| 2026-11-01 | v1 草案 | 初稿：`user_version` 版本轴 + 有序迁移（SQL / Go 函数）+ 每条一事务 + 老库 `0001`/`0002` 桥接 + 迁移前备份 + 降级 fail-fast；与服务端版本解耦，协议协商延后并给出触发条件 |
| 2026-11-01 | v1.1 | 落地 S1 + S2：`store/migrate.go` + `migrations/0001_init.sql` + `0002` 补列；`OpenAt` 启动迁移；备份（全新安装跳过）、降级 fail-fast、启动错误弹窗 + `desktop-error.log`；`api.GetEndpoint` 把 manifest 的 404/405 映射为 `ErrServerTooOld`；`desktop-sync.md` §4 与代码对齐 |
| 2026-11-02 | v1.2 | 追加 `0003_local_media.sql`：`local_attachment` 本地图片元数据表；二进制在 `media/local/`，不参与同步（`docs/desktop-media.md`） |
