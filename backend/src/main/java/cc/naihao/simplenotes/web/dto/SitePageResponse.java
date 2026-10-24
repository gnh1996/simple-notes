package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 独立页面（公开 / 管理端共用）。 */
@Schema(requiredProperties = { "id", "slug", "title", "content", "visible", "sort" })
public record SitePageResponse(
        String id,
        String slug,
        String title,
        String content,
        boolean visible,
        int sort,
        Instant updatedAt) {
}
