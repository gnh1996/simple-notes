package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.NoteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 记事本详情。 */
@Schema(requiredProperties = { "id", "title", "content", "tags", "pinned", "status", "version" })
public record NoteDetail(
        String id,
        String title,
        String content,
        String folderId,
        String folderName,
        List<TagBrief> tags,
        boolean pinned,
        NoteStatus status,
        Instant createTime,
        Instant updateTime,
        long version) {
}
