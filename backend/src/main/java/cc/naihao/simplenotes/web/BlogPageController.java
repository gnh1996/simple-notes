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
import java.net.URI;
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
 * 博客页面组装（docs/blog-runtime-theme.md §5.1）：后端只组装壳（站点配置 + 主题
 * HTML/CSS），runtime 由 Vite 构建后随 jar 同源提供。
 *
 * <ul>
 *   <li>主题版本由 {@code site_setting.theme.id} + {@code site_theme.active_version_id} 决定；</li>
 *   <li>未配置主题 / 契约不兼容时回落随代码发布的内置默认主题；</li>
 *   <li>壳与路由无关（runtime 自己读 location），因此 ETag 只需主题 + 配置 + 资产版本；</li>
 *   <li>页面路由归 runtime：后端只做 history fallback（{@link #SHELL_PATH}），不登记具体页面；</li>
 *   <li>预览通过 {@code previewTheme} + 短期签名 token 选中草稿版本，且不进入任何缓存。</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BlogPageController {

    /** 壳模板结构版本：模板 / 注入数据变化时递增，避免 ETag 命中旧壳。 */
    private static final int SHELL_VERSION = 3;

    /**
     * 首屏加载层样式：必须内联在壳里（runtime.css 随 JS 到达，赶不上首帧）。
     * 排在主题 CSS 之前，主题可用 {@code [data-blog-boot]{display:none}} 选择纯空白首帧。
     */
    private static final String BOOT_STYLE = """
            [data-blog-boot] {
              position: fixed;
              inset: 0;
              z-index: 200;
              display: grid;
              place-items: center;
              align-content: center;
              gap: 12px;
              background: var(--blog-bg, #fff);
              color: var(--blog-text-muted, #8a8a8a);
              font: 14px/1.7 system-ui, -apple-system, 'Segoe UI', sans-serif;
              text-align: center;
              opacity: 1;
              transition: opacity 180ms ease, visibility 0s linear 180ms;
            }
            [data-blog-boot] .blog-boot-bar {
              width: 120px;
              height: 2px;
              border-radius: 2px;
              background: linear-gradient(90deg, transparent, currentColor, transparent);
              background-size: 200% 100%;
              animation: blog-boot-shimmer 1.2s linear infinite;
              opacity: 0.7;
            }
            html[data-blog-ready='1'] [data-blog-boot] {
              opacity: 0;
              visibility: hidden;
              pointer-events: none;
            }
            @keyframes blog-boot-shimmer {
              from { background-position: 200% 0; }
              to { background-position: -200% 0; }
            }
            @media (prefers-reduced-motion: reduce) {
              [data-blog-boot] { transition: none; }
              [data-blog-boot] .blog-boot-bar { animation: none; }
            }
            """;

    /**
     * 壳路由（docs/blog-runtime-theme.md §4.5）：除保留前缀外，任意非 API 的 GET 都落到同一份壳，
     * 即 SPA 的 history fallback。页面路由由 runtime（{@code frontend/apps/blog/src/runtime.ts}）决定，
     * 后端不再逐个登记——新增前端路由无需改后端。
     *
     * <p>排除段说明：
     * <ul>
     *   <li>{@code api}：未知 API 必须是 401 / 404 JSON，不能返回 HTML；</li>
     *   <li>{@code blog}：jar 内 runtime 资源由静态资源 handler 提供，本 catch-all 会抢占它；</li>
     *   <li>{@code v3} / {@code error} / {@code favicon.svg}：契约、错误分发与根静态文件。</li>
     * </ul>
     *
     * <p>未知页面返回 200 + 壳，由 runtime 渲染 not-found（设计文档 §4.5 已声明 SEO 为非目标）；
     * 将来做发布时预渲染时再升级为真 404。
     */
    static final String SHELL_PATH = "/{path:^(?!api$|blog$|v3$|error$|favicon\\.svg$).*$}";

    private static final Pattern STYLE_BREAKOUT = Pattern.compile("</style", Pattern.CASE_INSENSITIVE);

    private final SiteSettingService settingService;
    private final ThemeService themeService;
    private final PreviewTokenService previewTokenService;
    private final SiteProperties siteProperties;
    private final ObjectMapper objectMapper;

    private volatile String defaultHtml;
    private volatile String defaultCss;

    @GetMapping({"/", SHELL_PATH, SHELL_PATH + "/**"})
    public void page(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String previewVersionId = request.getParameter("previewTheme");
        boolean preview = previewVersionId != null && !previewVersionId.isBlank();
        SiteThemeVersion theme;
        if (preview) {
            if (!previewTokenService.verify(previewVersionId, request.getParameter("token"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("预览链接无效或已过期");
                return;
            }
            theme = ensureContract(themeService.previewVersion(previewVersionId));
        } else {
            SiteThemeVersion active = themeService.activeVersion();
            theme = active == null ? null : ensureContract(active);
        }

        SiteResponse site = settingService.publicSite();
        String themeId = theme == null ? "default" : theme.getThemeId();
        int themeVersionNo = theme == null ? 0 : theme.getVersionNo();
        int themeContract = theme == null ? ThemeService.RUNTIME_CONTRACT : theme.getContract();

        String etag = "\"" + themeId + "-" + themeVersionNo + "-"
                + settingService.currentVersion() + "-" + assetBase() + "-" + SHELL_VERSION + "\"";
        String cacheControl = preview
                ? "no-store"
                : "public, max-age=0, s-maxage=60, stale-while-revalidate=600";

        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", cacheControl);
        response.setHeader("CDN-Cache-Control", cacheControl);
        if (!preview && etag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        String html = theme == null ? defaultHtml() : theme.getHtml();
        String css = theme == null ? defaultCss() : theme.getCss();

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Security-Policy", csp());
        response.setHeader("Referrer-Policy", preview ? "no-referrer" : "strict-origin-when-cross-origin");
        if (preview) {
            response.setHeader("X-Robots-Tag", "noindex");
        }
        response.getWriter().write(render(
                html,
                STYLE_BREAKOUT.matcher(css).replaceAll("<\\\\/style"),
                bootJson(site, themeId, themeVersionNo, themeContract),
                site,
                themeId,
                themeContract,
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
            String themeId, int themeContract, boolean preview) {
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
                  <style>[v-cloak]{display:none}</style>
                  <style id="blog-boot-style">%s</style>
                  <style id="blog-theme">%s</style>
                </head>
                <body data-theme-id="%s" data-runtime-contract="%s"%s>
                <noscript>
                  <style>[data-blog-boot],[data-blog-progress]{display:none}</style>
                  <p style="padding:24px;text-align:center;font:14px/1.7 system-ui,sans-serif">需要启用 JavaScript 才能加载文章内容。</p>
                </noscript>
                <div id="blog-boot" data-blog-boot role="status" aria-live="polite">
                  <span class="blog-boot-bar" aria-hidden="true"></span>
                  <span>正在加载…</span>
                </div>
                <div id="blog-progress" data-blog-progress aria-hidden="true"></div>
                %s
                <script type="module" src="%s/runtime.js"></script>
                <script>
                  setTimeout(function () {
                    var root = document.documentElement;
                    if (root.dataset.blogReady === '1') return;
                    var boot = document.getElementById('blog-boot');
                    if (!boot || boot.hasAttribute('data-error') || root.dataset.blogRuntime === '1') return;
                    boot.setAttribute('data-error', '1');
                    boot.style.setProperty('display', 'grid', 'important');
                    boot.style.setProperty('opacity', '1', 'important');
                    boot.style.setProperty('visibility', 'visible', 'important');
                    boot.textContent = '页面脚本加载失败，请刷新重试。';
                  }, 12000);
                </script>
                </body>
                </html>
                """.formatted(
                escape(site.description() == null ? "" : site.description()),
                favicon,
                escape(title),
                bootJson,
                BOOT_STYLE,
                themeCss,
                escape(themeId),
                themeContract,
                preview ? " data-preview=\"1\"" : "",
                themeHtml,
                escape(assetBase()));
    }

    private String bootJson(SiteResponse site, String themeId, int themeVersionNo, int themeContract) {
        Map<String, Object> boot = new LinkedHashMap<>();
        boot.put("site", site);
        boot.put("themeMode", site.themeMode());
        boot.put("adminOrigin", siteProperties.getAdminOrigin());
        boot.put("contract", ThemeService.RUNTIME_CONTRACT);
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("id", themeId);
        theme.put("versionNo", themeVersionNo);
        theme.put("contract", themeContract);
        boot.put("theme", theme);
        String json = objectMapper.writeValueAsString(boot);
        // 防止配置值中出现 </script> 提前闭合
        return json.replace("</", "<\\/");
    }

    private String csp() {
        String assetOrigin = originOf(assetBase());
        String extra = assetOrigin == null ? "" : " " + assetOrigin;
        String ws = assetOrigin == null ? "" : " " + assetOrigin.replaceFirst("^http", "ws");
        return "default-src 'self'; "
                + "script-src 'self' 'unsafe-inline' 'unsafe-eval'" + extra + "; "
                + "style-src 'self' 'unsafe-inline'" + extra + "; "
                + "font-src 'self' data:" + extra + "; "
                + "img-src 'self' data:; "
                + "connect-src 'self'" + extra + ws + "; "
                + "frame-ancestors 'self' " + siteProperties.getAdminOrigin();
    }

    private String assetBase() {
        String value = siteProperties.getAssetBase() == null ? "" : siteProperties.getAssetBase().trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
