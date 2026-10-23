package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开标签及已发布文章数。 */
@Schema(requiredProperties = { "id", "name", "count" })
public record TagCountResponse(String id, String name, long count) {
}
