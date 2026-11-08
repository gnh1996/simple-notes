package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import cc.naihao.simplenotes.config.AiConfig;
import cc.naihao.simplenotes.config.AiEnvironmentPostProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * AI fail-soft 回归（docs/theme-ai.md §5）：缺 key 时整个 OpenAI 装配链静默缺席，
 * 上下文照常启动；配齐时 ChatClient 可用。连接信息只有 spring.ai.openai.* 一份来源。
 */
class AiAutoConfigTest {

    private static AutoConfigurations aiAutoConfigs() {
        return AutoConfigurations.of(
                OpenAiChatAutoConfiguration.class,
                ToolCallingAutoConfiguration.class,
                ChatClientAutoConfiguration.class,
                ChatMemoryAutoConfiguration.class,
                AiConfig.class);
    }

    /**
     * postprocessor 在真实启动里做的事：缺 key 时 OpenAI + ChatClient Builder 两个装配根本不进候选。
     * ChatMemory/ToolCalling 装配保留（无模型依赖）。
     *（runner 里手动注册的自动装配不受 {@code spring.autoconfigure.exclude} 约束，故直接不注册来等价模拟。）
     */
    private static AutoConfigurations aiAutoConfigsWithoutOpenAi() {
        return AutoConfigurations.of(
                ToolCallingAutoConfiguration.class,
                ChatMemoryAutoConfiguration.class,
                AiConfig.class);
    }

    @Test
    @DisplayName("配齐 key/model/base-url 时 ChatClient 可用")
    void loadsWithKey() {
        new ApplicationContextRunner()
                .withConfiguration(aiAutoConfigs())
                .withUserConfiguration(CaffeineChatMemoryRepository.class)
                .withPropertyValues(
                        "spring.ai.openai.base-url=http://127.0.0.1:1",
                        "spring.ai.openai.api-key=test-key",
                        "spring.ai.openai.chat.model=test-model")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context).hasSingleBean(ChatMemory.class);
                });
    }

    @Test
    @DisplayName("缺 key 时无 ChatClient bean，但上下文照常启动")
    void skipsWithoutKey() {
        new ApplicationContextRunner()
                .withConfiguration(aiAutoConfigsWithoutOpenAi())
                .withUserConfiguration(CaffeineChatMemoryRepository.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                    assertThat(context).hasSingleBean(ChatMemory.class);
                });
    }

    @Test
    @DisplayName("postprocessor：缺任一连接值则加 exclude，配齐则不动")
    void postprocessorExcludesWhenIncomplete() {
        AiEnvironmentPostProcessor processor = new AiEnvironmentPostProcessor();

        MockEnvironment incomplete = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "")
                .withProperty("spring.ai.openai.chat.model", "m")
                .withProperty("spring.ai.openai.base-url", "http://x");
        processor.postProcessEnvironment(incomplete, null);
        assertThat(incomplete.getProperty(AiEnvironmentPostProcessor.EXCLUDE_KEY))
                .contains("OpenAiChatAutoConfiguration")
                .contains("ChatClientAutoConfiguration");

        MockEnvironment complete = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "k")
                .withProperty("spring.ai.openai.chat.model", "m")
                .withProperty("spring.ai.openai.base-url", "http://x");
        processor.postProcessEnvironment(complete, null);
        assertThat(complete.getProperty(AiEnvironmentPostProcessor.EXCLUDE_KEY)).isNull();
    }
}
