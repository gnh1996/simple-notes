-- 0003 本地图片附件（docs/desktop-media.md）
--
-- 只记录桌面端本地笔记 / 临时文件导入后的图片元数据；二进制文件放在
-- desktop.db 同级的 media/local/ 目录，不参与服务端同步。

CREATE TABLE IF NOT EXISTS local_attachment (
    id            TEXT PRIMARY KEY,
    scope_id      TEXT NOT NULL DEFAULT 'local',
    original_name TEXT NOT NULL,
    mime_type     TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL,
    storage_key   TEXT NOT NULL,
    create_time   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_local_attachment_create
    ON local_attachment (create_time DESC);
