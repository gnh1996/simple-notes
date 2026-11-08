package cc.naihao.simplenotes.site;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * TTL 版 ChatMemory 仓库（docs/theme-ai.md §5）。
 *
 * <p>替代自动装配默认的无界内存实现：会话 2 小时无访问即过期（上限 1000，
 * 与草稿预览 TTL 同量级思路），抽屉关了不清理也不会泄漏；重启即清（与原语义一致）。
 *
 * <p>v2.1 起在落库前压缩大消息：整篇 HTML/CSS 只存摘要/计划，当前主题由
 * {@link AiThemeSessionStore} 单独保存，避免 ChatMemory 反复把几十 KB 主题塞回上下文。
 */
@Component
public class CaffeineChatMemoryRepository implements ChatMemoryRepository {

    private static final int COMPACT_THRESHOLD = 2_000;

    private final Cache<String, List<Message>> cache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    @Override
    public List<String> findConversationIds() {
        return List.copyOf(cache.asMap().keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = cache.getIfPresent(conversationId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<Message> compacted = messages.stream().map(this::compact).toList();
        cache.put(conversationId, compacted);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        cache.invalidate(conversationId);
    }

    private Message compact(Message message) {
        String text = message.getText();
        if (text == null || text.length() <= COMPACT_THRESHOLD) {
            return message;
        }
        String role = message.getMessageType() == MessageType.USER ? "user" : "assistant";
        String compacted = AiThemePrompt.compactForMemory(text, role);
        if (message instanceof AssistantMessage) {
            return new AssistantMessage(compacted);
        }
        if (message instanceof UserMessage) {
            return new UserMessage(compacted);
        }
        return message;
    }
}
