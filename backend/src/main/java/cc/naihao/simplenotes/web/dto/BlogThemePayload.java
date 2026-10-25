package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开主题负载（docs/blog-runtime-theme.md §9）：运行时启动时按需取用。 */
@Schema(requiredProperties = { "id", "versionNo", "contract", "html", "css" })
public record BlogThemePayload(
        String id,
        int versionNo,
        int contract,
        String html,
        String css) {
}
