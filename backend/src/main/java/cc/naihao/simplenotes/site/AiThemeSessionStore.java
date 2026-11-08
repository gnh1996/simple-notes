package cc.naihao.simplenotes.site;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI 主题会话的“当前主题”（docs/theme-ai.md §2/§5，v2.1）。
 *
 * <p>与 ChatMemory 分开：ChatMemory 只存压缩后的对话摘要，这里存完整 HTML/CSS。
 * 后续轮只把当前主题放 system prompt 一次，不再让前端每轮发 baseHtml、也不再让
 * memory 重复保存整篇主题。会话 2 小时无访问过期，重启即清。
 */
@Service
public class AiThemeSessionStore {

    private final Cache<String, ThemeState> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public ThemeState get(String conversationId) {
        return conversationId == null ? null : cache.getIfPresent(conversationId);
    }

    public void put(String conversationId, String html, String css, int contract, String summary, List<String> plan) {
        cache.put(conversationId, new ThemeState(
                html == null ? "" : html,
                css == null ? "" : css,
                contract,
                summary == null ? "" : summary,
                plan == null ? List.of() : List.copyOf(plan),
                Instant.now()));
    }

    public void clear(String conversationId) {
        if (conversationId != null) {
            cache.invalidate(conversationId);
        }
    }

    public record ThemeState(
            String html,
            String css,
            int contract,
            String summary,
            List<String> plan,
            Instant updatedAt) {
    }
}
