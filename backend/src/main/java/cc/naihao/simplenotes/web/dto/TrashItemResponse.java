package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 回收站列表项。 */
@Schema(requiredProperties = { "id", "kind", "title", "deletedAt" })
public record TrashItemResponse(
        String id,
        String kind,
        String title,
        String publishStatus,
        Instant deletedAt) {
}
