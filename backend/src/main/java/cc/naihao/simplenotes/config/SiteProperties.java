package cc.naihao.simplenotes.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点配置（{@code simple-notes.site.*}，docs/blog-runtime-theme.md §5.2）。
 */
@ConfigurationProperties(prefix = "simple-notes.site")
@Getter
@Setter
public class SiteProperties {

    /** 允许嵌入预览 iframe 的管理端源（用于 CSP frame-ancestors 与 postMessage 白名单） */
    private String adminOrigin = "http://localhost:5173";

    /**
     * blog runtime 构建产物基址（docs/blog-runtime-theme.md §5.3）。
     *
     * <p>生产默认 {@code /blog}：由后端从 jar 内 {@code static/blog/} 提供；
     * 开发期用 {@code BLOG_ASSET_BASE=http://localhost:5174} 指向 Vite dev server 获得 HMR。
     */
    private String assetBase = "/blog";

    /** 主题预览 token 有效期 */
    private Duration previewTtl = Duration.ofMinutes(30);
}
