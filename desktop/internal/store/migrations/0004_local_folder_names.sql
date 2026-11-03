-- 同一 scope 内文件夹名唯一（大小写不敏感）。
-- 只约束本地自建文件夹（scope_id='local'）：
--   * 服务端镜像已由后端 uk_note_folder_owner_name 保证；
--   * 服务端文件夹的离线改名（dirty 行）与远端新增文件夹可能短暂同名，
--     用部分索引避免同步时因本地未推送的改名而整体失败。
-- scope_id 区分本地与各账号，因此跨 scope 允许同名。
CREATE UNIQUE INDEX IF NOT EXISTS uk_folder_local_scope_name
    ON folder_local (scope_id, lower(name))
    WHERE scope_id = 'local';
