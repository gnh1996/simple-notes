package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 批量保存站点配置（docs/blog-runtime-theme.md §6.2）。
 *
 * <p>只提交需要变更的键；值等于默认值的键会被物理删除，保持“空表 = 默认值”。
 */
public record UpdateSiteSettingsRequest(
        @NotNull(message = "缺少配置版本") Long version,
        @NotNull(message = "缺少配置内容") Map<String, Object> values) {
}
