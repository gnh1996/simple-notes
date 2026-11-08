package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.config.SiteProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 主题草稿临时缓存（docs/theme-ai.md §4）：流式生成的中间产物落这里，用于整页预览，
 * 定版后由前端调既有 {@code createVersion} 落库。TTL 对齐 {@code site.preview-ttl}，
 * 内存级实现（重启即清），不走 Flyway、不污染 {@code site_theme_version}。
 */
@Service
@RequiredArgsConstructor
public class AiDraftCache {

    private final SiteProperties siteProperties;

    private volatile Cache<String, Draft> cache;

    /** 存草稿并返回 draftId（previewDraftId + token 分支消费） */
    public String put(String html, String css, int contract) {
        String id = "draft-" + UUID.randomUUID();
        cache().put(id, new Draft(html, css, contract, Instant.now()));
        return id;
    }

    public Draft get(String draftId) {
        if (draftId == null) {
            return null;
        }
        return cache().getIfPresent(draftId);
    }

    private Cache<String, Draft> cache() {
        Cache<String, Draft> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = Caffeine.newBuilder()
                            .maximumSize(100)
                            .expireAfterWrite(siteProperties.getPreviewTtl())
                            .build();
                    cache = current;
                }
            }
        }
        return current;
    }

    public record Draft(String html, String css, int contract, Instant createTime) {
    }
}
