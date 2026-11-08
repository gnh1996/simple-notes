package cc.naihao.simplenotes.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 主题 AI 的 Spring AI 用重装配（docs/theme-ai.md §5）。
 *
 * <p>必须走自动装配（见 AutoConfiguration.imports）：普通 {@code @Configuration} 先于自动装配执行，
 * {@code @ConditionalOnBean(Builder)} 在那里永远看不到后面的 bean。排序保证 OpenAI 装配链先行，
 * 缺 key 时（{@code AiEnvironmentPostProcessor} 已排除）本类静默跳过，启动不受影响。
 */
@AutoConfiguration
@AutoConfigureAfter({
        OpenAiChatAutoConfiguration.class,
        ChatClientAutoConfiguration.class,
        ChatMemoryAutoConfiguration.class
})
public class AiConfig {

    /**
     * 主题对话客户端：只装记忆 advisor。日志由 {@code AiThemeService} 按轮次输出
     * （SimpleLoggerAdvisor 会把整篇主题写进日志，v2.1 移除）。
     * conversationId 由调用方按请求设置（见 {@code AiThemeService}）。
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    public ChatClient aiThemeChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();
    }

    /** SSE 扇出 + 阻塞聚合跑在虚拟线程上（Java 25）。AI 未配置时闲置，随上下文关闭。 */
    @Bean(destroyMethod = "close")
    public ExecutorService aiThemeExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-theme-", 0).factory());
    }
}
