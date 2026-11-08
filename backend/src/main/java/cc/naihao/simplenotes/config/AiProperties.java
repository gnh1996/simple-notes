package cc.naihao.simplenotes.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主题 AI 对话配置（{@code simple-notes.ai.*}，docs/theme-ai.md）。
 *
 * <p>全部走配置文件 / 环境变量，不入库（密钥禁则见根 AGENTS.md）。key 缺失时 AI 接口 400，
 * 不影响启动（fail-soft：单租户博客，未配 AI 也能正常用主题管理）。
 */
@ConfigurationProperties(prefix = "simple-notes.ai")
@Getter
@Setter
public class AiProperties {

    /** OpenAI-compatible base URL，如 https://api.xxx.com/v1（环境变量 AI_BASE_URL） */
    private String baseUrl = "";

    /** API Key（环境变量 AI_API_KEY）：只转发，不落库、不落日志 */
    private String apiKey = "";

    /** 模型名（环境变量 AI_MODEL） */
    private String model = "";

    /** 单轮流式超时（含自动修，环境变量 AI_TIMEOUT） */
    private Duration timeout = Duration.ofSeconds(90);

    /** 生成 + 自动修总轮数上限（环境变量 AI_MAX_ROUNDS，首轮生成 + 最多 maxRounds-1 轮修复） */
    private int maxRounds = 2;

    /** 随请求带入的最多历史轮数（环境变量 AI_MAX_HISTORY_TURNS，会话态在前端） */
    private int maxHistoryTurns = 10;

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}
