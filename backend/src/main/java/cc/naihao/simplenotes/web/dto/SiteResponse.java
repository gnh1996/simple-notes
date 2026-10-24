package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 公开站点配置（docs/blog-runtime-theme.md §3.5）：页面注入与 {@code GET /api/blog/site} 共用。
 */
@Schema(requiredProperties = {
        "name", "tagline", "description", "logoUrl", "faviconUrl", "baseUrl",
        "icp", "policeRecord", "author", "social", "nav", "themeMode", "themeOverrides",
        "footerText", "pageSize" })
public record SiteResponse(
        String name,
        String tagline,
        String description,
        String logoUrl,
        String faviconUrl,
        String baseUrl,
        String icp,
        String policeRecord,
        AuthorResponse author,
        List<SocialLinkResponse> social,
        List<NavItemResponse> nav,
        String themeMode,
        Object themeOverrides,
        String footerText,
        int pageSize) {
}
