package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.PublishStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 后台文章详情：当前草稿 + 已发布快照摘要 + hasUnpublishedChanges。
 *
 * <p>公开正文不在这里返回；后台编辑器预览使用当前草稿。
 */
@Schema(requiredProperties = { "id", "title", "content", "tags", "pinned", "publishStatus",
        "hasUnpublishedChanges", "version" })
public record PostDetail(
        String id,
        String title,
        String content,
        String summary,
        String slug,
        String publicUrl,
        String coverAttachmentId,
        String coverUrl,
        List<TagBrief> tags,
        boolean pinned,
        PublishStatus publishStatus,
        boolean hasUnpublishedChanges,
        String publishedRevisionId,
        Integer publishedRevisionNo,
        String publishedTitle,
        Instant firstPublishedAt,
        Instant lastPublishedAt,
        Instant draftModifiedAt,
        Instant createTime,
        Instant updateTime,
        long version) {
}
