package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 记事本详情。 */
@Schema(requiredProperties = { "id", "title", "content", "tags", "pinned", "version" })
public record NoteDetail(
        String id,
        String title,
        String content,
        String folderId,
        String folderName,
        List<TagBrief> tags,
        boolean pinned,
        Instant createTime,
        Instant updateTime,
        long version) {
}
