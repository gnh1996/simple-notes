package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 公开文章详情：正文来自发布快照；prevPost / nextPost 由当前排序上下文计算。 */
@Schema(requiredProperties = { "slug", "title", "content", "summary", "tags", "pinned", "authorName",
        "firstPublishedAt", "lastPublishedAt", "displayAt", "readingMinutes" })
public record PublishedPostDetail(
        String slug,
        String title,
        String content,
        String summary,
        String coverUrl,
        List<TagBrief> tags,
        boolean pinned,
        String authorName,
        Instant firstPublishedAt,
        Instant lastPublishedAt,
        Instant authoredAt,
        Instant displayAt,
        int readingMinutes,
        PublishedPostSummary prevPost,
        PublishedPostSummary nextPost) {
}
