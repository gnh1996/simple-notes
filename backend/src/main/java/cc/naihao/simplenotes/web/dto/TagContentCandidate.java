package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteStatus;
import cc.naihao.simplenotes.domain.PublishStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 标签管理页可添加的内容候选（当前草稿尚未使用该标签）。 */
@Schema(requiredProperties = { "id", "kind", "title", "status", "hasUnpublishedChanges" })
public record TagContentCandidate(
        String id,
        NoteKind kind,
        String title,
        NoteStatus status,
        PublishStatus publishStatus,
        boolean hasUnpublishedChanges,
        Instant updateTime) {
}
