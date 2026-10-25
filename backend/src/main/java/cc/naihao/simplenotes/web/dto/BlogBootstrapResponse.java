package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 博客启动数据（docs/blog-runtime-theme.md §9）：开发期 / 无壳注入时的唯一启动入口。
 *
 * <p>生产环境由 {@code BlogPageController} 把 site 与主题直接注入 HTML；本接口主要服务
 * Vite dev、预览与未来的桌面端复用。
 */
@Schema(requiredProperties = {
        "site", "runtimeContract", "adminOrigin", "preview", "settingVersion" })
public record BlogBootstrapResponse(
        SiteResponse site,
        BlogThemePayload theme,
        int runtimeContract,
        String adminOrigin,
        boolean preview,
        long settingVersion) {
}
