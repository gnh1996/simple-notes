package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 附件存储状态（docs/attachment-storage.md §7.5）。 */
@Schema(requiredProperties = { "mode", "availableModes", "counts", "health" })
public record StorageStatusResponse(
        String mode,
        List<String> availableModes,
        Map<String, Long> counts,
        Map<String, String> health) {
}
