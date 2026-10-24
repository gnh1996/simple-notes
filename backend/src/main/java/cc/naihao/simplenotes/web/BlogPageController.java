package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.config.SiteProperties;
import cc.naihao.simplenotes.domain.SiteThemeVersion;
import cc.naihao.simplenotes.site.PreviewTokenService;
import cc.naihao.simplenotes.site.SiteSettingService;
import cc.naihao.simplenotes.site.ThemeService;
import cc.naihao.simplenotes.web.dto.SiteResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 博客页面组装（docs/blog-runtime-theme.md §5.1）：后端注入站点配置与主题，返回整页。
 *
 * <ul>
 *   <li>主题版本由 {@code site_setting.theme.id} + {@code site_theme.active_version_id} 决定；</li>
 *   <li>未配置主题时回落随代码发布的内置默认主题；</li>
 *   <li>预览通过 {@code previewTheme} + 短期签名 token 选中草稿版本。</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BlogPageController {

    private static final Pattern STYLE_BREAKOUT = Pattern.compile("</style", Pattern.CASE_INSENSITIVE);

    private final SiteSettingService settingService;
    private final ThemeService themeService;
    private final PreviewTokenService previewTokenService;
    private final SiteProperties siteProperties;
    private final ObjectMapper objectMapper;

    private volatile String defaultHtml;
    private volatile String defaultCss;

    @GetMapping({
            "/",
            "/posts/{slug}",
            "/tags",
            "/tags/{id}",
            "/archives",
            "/search",
            "/about",
            "/p/{slug}"
    })
    public void page(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String previewVersionId = request.getParameter("previewTheme");
        boolean preview = previewVersionId != null;
        SiteThemeVersion theme;
        if (preview) {
            if (!previewTokenService.verify(previewVersionId, request.getParameter("token"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("预览链接无效或已过期");
                return;
            }
            theme = ensureContract(themeService.previewVersion(previewVersionId));
        } else {
            theme = themeService.activeVersion();
            theme = theme == null ? null : ensureContract(theme);
        }

        SiteResponse site = settingService.publicSite();
        String html = theme == null ? defaultHtml() : theme.getHtml();
        String css = theme == null ? defaultCss() : theme.getCss();
        String themeId = theme == null ? "default" : theme.getThemeId();

        String boot = bootJson(site, request);

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", csp());
        if (preview) {
            response.setHeader("X-Robots-Tag", "noindex");
        }
        response.getWriter().write(render(
                html,
                STYLE_BREAKOUT.matcher(css).replaceAll("<\\\\/style"),
                boot,
                site,
                themeId,
                preview));
    }

    private SiteThemeVersion ensureContract(SiteThemeVersion version) {
        if (version.getContract() > ThemeService.RUNTIME_CONTRACT) {
            log.warn("主题版本 {} 契约 {} 高于运行时 {}，回落内置默认主题",
                    version.getId(), version.getContract(), ThemeService.RUNTIME_CONTRACT);
            return null;
        }
        return version;
    }

    private String render(String themeHtml, String themeCss, String bootJson, SiteResponse site,
            String themeId, boolean preview) {
        String favicon = site.faviconUrl() == null ? "/favicon.svg" : escape(site.faviconUrl());
        String title = site.name() == null || site.name().isBlank() ? "Simple Notes" : site.name();
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
                  <meta name="description" content="%s" />
                  <meta name="theme-color" content="#ffffff" />
                  <link rel="icon" href="%s" />
                  <title>%s</title>
                  <script>window.__BLOG_BOOT__ = %s;</script>
                  <script>
                    (function () {
                      try {
                        var mode = window.__BLOG_BOOT__.themeMode || 'system';
                        var stored = localStorage.getItem('sn.blog.colorMode');
                        var resolved = stored && stored !== 'system' ? stored
                          : (mode === 'system'
                              ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
                              : mode);
                        document.documentElement.dataset.colorMode = resolved;
                      } catch (e) { /* 隐私模式下忽略 */ }
                    })();
                  </script>
                  <link rel="stylesheet" href="/blog/runtime.css" />
                  <style>%s</style>
                  <script type="importmap">{"imports":{"vue":"/blog/vendor/vue.esm-browser.prod.js","vue-router":"/blog/vendor/vue-router.esm-browser.prod.js"}}</script>
                </head>
                <body data-theme-id="%s"%s>
                %s
                <script src="/blog/vendor/markdown-it.min.js"></script>
                <script type="module" src="/blog/runtime.js"></script>
                </body>
                </html>
                """.formatted(
                escape(site.description() == null ? "" : site.description()),
                favicon,
                escape(title),
                bootJson,
                themeCss,
                escape(themeId),
                preview ? " data-preview=\"1\"" : "",
                themeHtml);
    }

    private String bootJson(SiteResponse site, HttpServletRequest request) {
        Map<String, Object> boot = new LinkedHashMap<>();
        boot.put("site", site);
        boot.put("themeMode", site.themeMode());
        boot.put("adminOrigin", siteProperties.getAdminOrigin());
        boot.put("path", request.getRequestURI());
        String json = objectMapper.writeValueAsString(boot);
        // 防止配置值中出现 </script> 提前闭合
        return json.replace("</", "<\\/");
    }

    private String csp() {
        return "default-src 'self'; "
                + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "connect-src 'self'; "
                + "frame-ancestors 'self' " + siteProperties.getAdminOrigin();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String defaultHtml() {
        if (defaultHtml == null) {
            defaultHtml = readResource("blog/default-theme/theme.html");
        }
        return defaultHtml;
    }

    private String defaultCss() {
        if (defaultCss == null) {
            defaultCss = readResource("blog/default-theme/theme.css");
        }
        return defaultCss;
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("缺少内置主题资源：" + path, e);
        }
    }
}
