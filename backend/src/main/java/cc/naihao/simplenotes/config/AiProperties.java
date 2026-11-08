package cc.naihao.simplenotes.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主题 AI 对话编排参数（{@code simple-notes.ai.*}，docs/theme-ai.md）。
 *
 * <p>连接信息（base-url/key/model）只有一份来源：{@code spring.ai.openai.*}（AI_* 桥接），
 * 缺 key 时 {@code AiEnvironmentPostProcessor} 排除 OpenAI chat / ChatClient Builder 装配
 *（其余模型类型在 application.yml 显式 none）；“是否可用”看 {@code aiThemeChatClient} bean 在不在。
 */
@ConfigurationProperties(prefix = "simple-notes.ai")
@Getter
@Setter
public class AiProperties {

    /** 单轮流式总限时（环境变量 AI_TIMEOUT）：慢模型输出整套主题要几分钟，默认 300s */
    private Duration timeout = Duration.ofSeconds(300);

    /** 生成 + 自动修总轮数上限（环境变量 AI_MAX_ROUNDS，首轮生成 + 最多 maxRounds-1 轮修复） */
    private int maxRounds = 2;

    /** 历史种子上限（环境变量 AI_MAX_HISTORY_TURNS）：后端 memory 未命中时，一次性写入的最早轮数 */
    private int maxHistoryTurns = 10;

    /** 单轮最大输出 token（环境变量 AI_MAX_TOKENS；0 = 不发送，由端点默认） */
    private int maxTokens = 0;

    /**
     * thinking 开关（环境变量 AI_THINKING）：auto / enabled / disabled；空 = 不发送。
     * DeepSeek 在 thinking 模式下 reasoning 也计入 max_tokens；主题生成通常关闭 thinking 更快更省。
     */
    private String thinking = "";

    /** reasoning_effort（环境变量 AI_REASONING_EFFORT）：none / low / high / max；空 = 不发送 */
    private String reasoningEffort = "";

    /**
     * 非空时把每轮实际发送的 system / user / ChatMemory 写入该目录（环境变量 AI_PROMPT_DUMP_DIR）。
     * 仅用于本地调试，默认关闭；不会写入 API Key。
     */
    private String promptDumpDir = "";
}
