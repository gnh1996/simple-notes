package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 服务端版本自证（docs/release.md §2）：发布版本 + 构建元信息 + 契约代际。
 *
 * <p>公开接口，不含任何敏感信息。product 版本只回答"这是哪次发布"，
 * 兼容性判断仍走桌面协议号（docs/desktop-local-schema.md §8），不要用本接口做功能门控。
 */
@Schema(requiredProperties = {"version", "commit", "buildTime", "contract"})
public record VersionResponse(
        @Schema(description = "产品发布版本：Git tag 去掉 v（如 0.2.0）；本地构建为 0.0.0-dev")
        String version,
        @Schema(description = "构建对应 commit 短哈希；未知时为 unknown")
        String commit,
        @Schema(description = "构建时间（ISO-8601）；未知时为 unknown")
        String buildTime,
        @Schema(description = "API 契约代际标签，与 OpenAPI info.version 一致")
        String contract) {
}
