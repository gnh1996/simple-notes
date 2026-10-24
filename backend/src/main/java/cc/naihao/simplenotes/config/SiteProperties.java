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

    /** 主题预览 token 有效期 */
    private Duration previewTtl = Duration.ofMinutes(30);
}
