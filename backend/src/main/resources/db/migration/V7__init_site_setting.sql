-- =============================================================================
-- 站点配置 KV（docs/blog-runtime-theme.md §6.2）
--
-- 约定：
--   * 无软删除 / 无 delete_flag：物理 upsert，移除即 DELETE（审计例外，见 backend/AGENTS.md）
--   * 类型 / 分组 / 默认值 / 校验 / 是否公开全部在代码侧注册表维护，不入库
--   * 复杂值以 JSON 字符串存储在 setting_value，并带形态版本 {"v":1,...}
-- =============================================================================

create table site_setting
(
    setting_key   varchar(100) not null primary key,
    setting_value text,
    create_time   timestamptz  not null,
    update_time   timestamptz,
    create_by     varchar(36),
    update_by     varchar(36)
);

comment on table site_setting is '站点配置 KV；空表等价于全部默认值';
