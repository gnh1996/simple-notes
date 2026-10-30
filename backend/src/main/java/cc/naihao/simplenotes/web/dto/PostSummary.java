package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.PublishStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 后台文章列表项。 */
@Schema(requiredProperties = { "id", "title", "tags", "pinned", "publishStatus", "hasUnpublishedChanges", "version" })
public record PostSummary(
        String id,
        String title,
        String summary,
        String slug,
        String coverUrl,
        List<TagBrief> tags,
        boolean pinned,
        PublishStatus publishStatus,
        boolean hasUnpublishedChanges,
        Instant firstPublishedAt,
        Instant lastPublishedAt,
        Instant authoredAt,
        Instant createTime,
        Instant updateTime,
        long version) {
}
