package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 主题预览 token：管理端拼 {@code {BLOG_URL}/?previewTheme=...&token=...} 后塞进 iframe。 */
@Schema(requiredProperties = { "versionId", "token", "expiresAt" })
public record PreviewTokenResponse(String versionId, String token, long expiresAt) {
}
