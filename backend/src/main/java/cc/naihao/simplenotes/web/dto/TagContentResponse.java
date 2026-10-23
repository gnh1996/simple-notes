package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteStatus;
import cc.naihao.simplenotes.domain.PublishStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 标签关联内容：draftTagged = 当前草稿是否使用；publishedTagged = 当前已发布快照是否使用。
 */
@Schema(requiredProperties = { "id", "kind", "title", "status", "hasUnpublishedChanges",
        "draftTagged", "publishedTagged" })
public record TagContentResponse(
        String id,
        NoteKind kind,
        String title,
        NoteStatus status,
        PublishStatus publishStatus,
        boolean hasUnpublishedChanges,
        boolean draftTagged,
        boolean publishedTagged,
        Instant updateTime) {
}
