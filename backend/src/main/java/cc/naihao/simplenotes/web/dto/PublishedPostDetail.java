package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 公开文章详情：正文来自发布快照。 */
@Schema(requiredProperties = { "slug", "title", "content", "summary", "tags", "authorName",
        "firstPublishedAt", "lastPublishedAt", "readingMinutes" })
public record PublishedPostDetail(
        String slug,
        String title,
        String content,
        String summary,
        String coverUrl,
        List<TagBrief> tags,
        String authorName,
        Instant firstPublishedAt,
        Instant lastPublishedAt,
        int readingMinutes) {
}
