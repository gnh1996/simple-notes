package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开归档分组（按首次发布时间的年月）。 */
@Schema(requiredProperties = { "year", "month", "count" })
public record ArchiveResponse(int year, int month, long count) {
}
