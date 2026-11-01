-- 0001 基线：桌面端本地库的完整结构（docs/desktop-local-schema.md §7.1）。
--
-- 本迁移对「全新安装」和「已有老库」都要成立：
--   - 新库：这里建出最终结构；
--   - 老库：表已存在，CREATE ... IF NOT EXISTS 整条跳过，缺列由 0002 补齐。
--
-- 因此本文件一旦发布不可修改；后续结构变化一律追加 0003、0004……
-- 时间列统一 epoch 毫秒（INTEGER）；app_kv / auth_session 的 TEXT 时间属历史遗留，见设计 §6.2。

CREATE TABLE IF NOT EXISTS app_kv (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    update_time TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 认证数据独立成表：永不参与内容同步 / 导出 / 备份（docs/auth-rbac.md §6.4）。
-- 单会话：id 固定为 1；未来多账号 / 多会话再扩展主键。
CREATE TABLE IF NOT EXISTS auth_session (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    token       TEXT NOT NULL,
    update_time TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 数据归属；当前只允许一个远端 scope 处于激活状态。
CREATE TABLE IF NOT EXISTS sync_scope (
    scope_id    TEXT PRIMARY KEY,
    server_url  TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    username    TEXT NOT NULL,
    nickname    TEXT NOT NULL DEFAULT '',
    active      INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

-- 笔记存根 / 缓存。content 为 NULL 表示正文尚未拉取（content_loaded = 0）。
CREATE TABLE IF NOT EXISTS note_local (
    scope_id                TEXT    NOT NULL,
    id                      TEXT    NOT NULL,
    kind                    TEXT    NOT NULL,
    title                   TEXT    NOT NULL,
    content                 TEXT,
    content_loaded          INTEGER NOT NULL DEFAULT 0,
    folder_id               TEXT,
    pinned                  INTEGER NOT NULL DEFAULT 0,
    tags_json               TEXT    NOT NULL DEFAULT '[]',
    publish_status          TEXT,
    has_unpublished_changes INTEGER,
    remote_version          INTEGER NOT NULL DEFAULT 0,
    base_version            INTEGER NOT NULL DEFAULT 0,
    local_hash              TEXT,
    base_hash               TEXT,
    dirty                   INTEGER NOT NULL DEFAULT 0,
    deleted                 INTEGER NOT NULL DEFAULT 0,
    remote_deleted          INTEGER NOT NULL DEFAULT 0,
    updated_at              INTEGER NOT NULL DEFAULT 0,
    synced_at               INTEGER,
    PRIMARY KEY (scope_id, id)
);
CREATE INDEX IF NOT EXISTS idx_note_local_list
    ON note_local (scope_id, kind, deleted, pinned DESC, updated_at DESC);

-- 文件夹镜像。删除是物理删除（没有 deleted 列），未推送的删除由 outbox 重放。
CREATE TABLE IF NOT EXISTS folder_local (
    scope_id    TEXT NOT NULL,
    id          TEXT NOT NULL,
    name        TEXT NOT NULL,
    sort        INTEGER NOT NULL DEFAULT 0,
    create_time INTEGER NOT NULL DEFAULT 0,
    dirty       INTEGER NOT NULL DEFAULT 0,
    updated_at  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_id, id)
);

-- 待重试的写操作。
CREATE TABLE IF NOT EXISTS outbox (
    scope_id      TEXT NOT NULL,
    entity        TEXT NOT NULL,
    id            TEXT NOT NULL,
    op            TEXT NOT NULL,
    payload       TEXT NOT NULL DEFAULT '',
    retry_count   INTEGER NOT NULL DEFAULT 0,
    next_retry_at INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    PRIMARY KEY (scope_id, entity, id)
);

-- 最近打开的本地文件：只记录路径 / 文件名 / 时间，不存正文（docs/desktop-local-file.md §3.5）。
CREATE TABLE IF NOT EXISTS recent_file (
    path      TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    opened_at INTEGER NOT NULL
);

-- 桌面端界面状态：标签顺序 / 侧栏宽度等；不参与同步，不存令牌与正文。
CREATE TABLE IF NOT EXISTS ui_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
