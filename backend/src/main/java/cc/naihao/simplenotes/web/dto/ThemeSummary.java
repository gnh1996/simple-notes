package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 主题列表项：身份 + 当前生效版本摘要。 */
@Schema(requiredProperties = {
        "id", "name", "activeVersionId", "activeVersionNo", "contract", "isActiveSiteTheme", "updatedAt" })
public record ThemeSummary(
        String id,
        String name,
        String activeVersionId,
        Integer activeVersionNo,
        Integer contract,
        boolean isActiveSiteTheme,
        Instant updatedAt) {
}
