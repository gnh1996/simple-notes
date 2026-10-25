package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.SiteProperties;
import cc.naihao.simplenotes.domain.SiteThemeVersion;
import cc.naihao.simplenotes.site.PreviewTokenService;
import cc.naihao.simplenotes.site.SiteSettingService;
import cc.naihao.simplenotes.site.ThemeService;
import cc.naihao.simplenotes.web.dto.BlogBootstrapResponse;
import cc.naihao.simplenotes.web.dto.BlogThemePayload;
import cc.naihao.simplenotes.web.dto.SiteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 博客启动数据结构（docs/blog-runtime-theme.md §9）：匿名可读。
 *
 * <p>生产首屏由 {@code BlogPageController} 把 site + 主题直接注入 HTML；本接口保留为
 * 调试 / agent / 未来桌面端的结构化读取入口，不参与 runtime 启动。</p>
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogBootstrapController {

    private final SiteSettingService settingService;
    private final ThemeService themeService;
    private final PreviewTokenService previewTokenService;
    private final SiteProperties siteProperties;

    @GetMapping("/bootstrap")
    public ResponseEntity<BlogBootstrapResponse> bootstrap(
            @RequestParam(required = false) String previewTheme,
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        boolean preview = previewTheme != null && !previewTheme.isBlank();
        SiteThemeVersion version;
        if (preview) {
            if (!previewTokenService.verify(previewTheme, token)) {
                throw ApiException.permissionDenied("预览链接无效或已过期");
            }
            version = themeService.previewVersion(previewTheme);
        } else {
            version = themeService.activeVersion();
        }
        if (version != null && version.getContract() > ThemeService.RUNTIME_CONTRACT) {
            version = null;
        }

        SiteResponse site = settingService.publicSite();
        long settingVersion = settingService.currentVersion();
        BlogThemePayload theme = version == null ? null : new BlogThemePayload(
                version.getThemeId(),
                version.getVersionNo(),
                version.getContract(),
                version.getHtml(),
                version.getCss());

        String etag = "\"" + (version == null ? "default" : version.getThemeId())
                + "-" + (version == null ? 0 : version.getVersionNo())
                + "-" + settingVersion + "\"";

        if (preview) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("CDN-Cache-Control", "no-store")
                    .header("X-Robots-Tag", "noindex")
                    .header("Referrer-Policy", "no-referrer")
                    .body(new BlogBootstrapResponse(
                            site, theme, ThemeService.RUNTIME_CONTRACT,
                            siteProperties.getAdminOrigin(), true, settingVersion));
        }
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("CDN-Cache-Control", "max-age=60, stale-while-revalidate=600")
                .header("Referrer-Policy", "strict-origin-when-cross-origin")
                .body(new BlogBootstrapResponse(
                        site, theme, ThemeService.RUNTIME_CONTRACT,
                        siteProperties.getAdminOrigin(), false, settingVersion));
    }
}
