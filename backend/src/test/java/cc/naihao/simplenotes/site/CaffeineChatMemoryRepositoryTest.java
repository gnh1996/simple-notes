package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** ChatMemory 压缩（docs/theme-ai.md §5，v2.1）：整篇主题不进记忆。 */
class CaffeineChatMemoryRepositoryTest {

    @Test
    @DisplayName("大 assistant 消息只保留摘要/计划，不再保存整篇 HTML/CSS")
    void compactsLargeAssistantMessage() {
        CaffeineChatMemoryRepository repository = new CaffeineChatMemoryRepository();
        String output = """
                <plan>- 改暗色</plan>
                这是说明。
                ```html
                <div id="app"><div style="height:9999px">%s</div></div>
                ```
                ```css
                .a { color: red; }
                ```
                """.formatted("x".repeat(2_500));
        repository.saveAll("c1", List.of(new AssistantMessage(output)));

        String stored = repository.findByConversationId("c1").getFirst().getText();
        assertThat(stored).contains("这是说明").contains("改暗色");
        assertThat(stored).doesNotContain("9999px").doesNotContain(".a { color");
    }

    @Test
    @DisplayName("短消息原样保留；会话之间隔离")
    void keepsShortMessagesAndSeparatesConversations() {
        CaffeineChatMemoryRepository repository = new CaffeineChatMemoryRepository();
        repository.saveAll("c1", List.of(new UserMessage("把圆角改大")));
        repository.saveAll("c2", List.of(new UserMessage("另一段对话")));

        assertThat(repository.findByConversationId("c1").getFirst().getText()).isEqualTo("把圆角改大");
        assertThat(repository.findByConversationId("c2").getFirst().getText()).isEqualTo("另一段对话");
        assertThat(repository.findByConversationId("missing")).isEmpty();
    }
}
