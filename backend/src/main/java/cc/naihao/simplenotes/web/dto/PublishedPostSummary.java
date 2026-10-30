package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 公开文章列表项：只来自已发布快照，不含正文。 */
@Schema(requiredProperties = { "slug", "title", "summary", "tags", "pinned", "firstPublishedAt",
        "lastPublishedAt", "displayAt", "readingMinutes" })
public record PublishedPostSummary(
        String slug,
        String title,
        String summary,
        String coverUrl,
        List<TagBrief> tags,
        boolean pinned,
        Instant firstPublishedAt,
        Instant lastPublishedAt,
        Instant authoredAt,
        Instant displayAt,
        int readingMinutes) {
}
