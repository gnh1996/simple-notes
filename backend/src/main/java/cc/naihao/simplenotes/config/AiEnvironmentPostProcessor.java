package cc.naihao.simplenotes.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * AI fail-soft 总开关（docs/theme-ai.md §5）。
 *
 * <p>{@code spring.ai.openai.api-key / chat.model / base-url} 任一为空时，
 * OpenAI 装配 + ChatClient Builder 装配都不启动——后者的 {@code chatClientBuilder} 是 prototype，
 * 强依赖 ChatModel，留它在候选里一旦被要就启动即崩（v1 没人要 Builder 才没事）。
 * 上下文照常完成；{@code AiThemeService} 以 {@code ChatClient} bean 缺席判定“未配置”，接口 400。
 * ChatMemory 装配保留（无模型依赖）。配齐后重启即用，不需要 dummy 占位、不需要“诚实副本”配置。
 */
public class AiEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 测试可见：拼到该 key 上，验证排除逻辑（AiAutoConfigTest） */
    public static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    /** 缺 key 时排除的装配 */
    static final List<String> EXCLUDED_WHEN_UNCONFIGURED = List.of(
            OpenAiChatAutoConfiguration.class.getName(),
            ChatClientAutoConfiguration.class.getName());

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        String model = environment.getProperty("spring.ai.openai.chat.model", "");
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        if (!apiKey.isBlank() && !model.isBlank() && !baseUrl.isBlank()) {
            return;
        }
        List<String> excluded = new ArrayList<>(EXCLUDED_WHEN_UNCONFIGURED);
        String existing = environment.getProperty(EXCLUDE_KEY, "");
        if (!existing.isBlank()) {
            for (String name : existing.split(",")) {
                if (!name.isBlank() && !excluded.contains(name.strip())) {
                    excluded.add(name.strip());
                }
            }
        }
        environment.getPropertySources()
                .addFirst(new MapPropertySource("aiAutoExclude", Map.of(EXCLUDE_KEY, String.join(",", excluded))));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
