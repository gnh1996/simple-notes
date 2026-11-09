package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 主题列表项：身份 + 当前生效版本摘要。
 *
 * <p>{@code activeVersionId} / {@code activeVersionNo} / {@code contract} 在主题尚未应用过版本时为空，
 * 不列入 requiredProperties（见 backend/AGENTS.md「API 契约」）。
 */
@Schema(requiredProperties = { "id", "name", "isActiveSiteTheme", "updatedAt" })
public record ThemeSummary(
        String id,
        String name,
        String activeVersionId,
        Integer activeVersionNo,
        Integer contract,
        boolean isActiveSiteTheme,
        Instant updatedAt) {
}
