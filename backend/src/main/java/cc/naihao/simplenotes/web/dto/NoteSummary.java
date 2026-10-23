package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.NoteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 记事本列表项（不返回正文）。 */
@Schema(requiredProperties = { "id", "title", "tags", "pinned", "status", "version" })
public record NoteSummary(
        String id,
        String title,
        String folderId,
        String folderName,
        List<TagBrief> tags,
        boolean pinned,
        NoteStatus status,
        Instant createTime,
        Instant updateTime,
        long version) {
}
