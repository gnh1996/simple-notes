package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.AiProperties;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * AI 摘要（docs/ai.md §3）：单轮、无状态、无会话记忆。
 *
 * <p>与主题 AI 的边界：这里不建 ChatClient bean、不挂 MessageChatMemoryAdvisor、
 * 不做流式与多轮修复；是否存在 {@link ChatModel} 即“AI 是否配置”，与启动期 fail-soft 一致。
 */
@Service
public class PostSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PostSummaryService.class);

    /** 摘要输出很短，固定小预算，避免推理模型把 token 全用在思考上 */
    private static final int MAX_TOKENS = 400;

    private final ObjectProvider<ChatModel> chatModels;
    private final ExecutorService aiExecutor;
    private final AiProperties ai;

    /** 单用户单并发（独立于主题 AI，避免两边互相阻塞；也防双击重复扣费） */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public PostSummaryService(ObjectProvider<ChatModel> chatModels,
            @Qualifier("aiThemeExecutor") ExecutorService aiExecutor,
            AiProperties ai) {
        this.chatModels = chatModels;
        this.aiExecutor = aiExecutor;
        this.ai = ai;
    }

    /** 连接信息缺失时 ChatModel 装配整体缺席（AiEnvironmentPostProcessor），前端据此置灰。 */
    public boolean enabled() {
        return chatModels.getIfAvailable() != null;
    }

    public String summarize(String userId, String title, String content) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw ApiException.aiUnavailable("AI 未配置：请填写 AI_BASE_URL / AI_API_KEY / AI_MODEL");
        }
        String key = userId == null || userId.isBlank() ? "anonymous" : userId;
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            throw ApiException.conflict("已有进行中的 AI 总结，请稍候再试");
        }
        long started = System.currentTimeMillis();
        String input = PostSummaryPrompt.clampInput(content);
        try {
            Future<String> task = aiExecutor.submit(() -> request(model, key, title, input));
            String raw;
            try {
                raw = task.get(ai.getSummaryTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                task.cancel(true);
                log.warn("AI 摘要超时 user={} inputChars={} timeoutMs={}",
                        mask(key), input.length(), ai.getSummaryTimeout().toMillis());
                throw ApiException.aiFailed("AI 总结超时，请重试");
            } catch (InterruptedException e) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw ApiException.aiFailed("AI 总结被中断，请重试");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("AI 摘要失败 user={} inputChars={} cause={}",
                        mask(key), input.length(), cause.getClass().getSimpleName());
                throw ApiException.aiFailed(friendly(cause));
            }
            String summary = PostSummaryPrompt.sanitize(raw);
            if (summary.isEmpty()) {
                log.warn("AI 摘要为空 user={} inputChars={}", mask(key), input.length());
                throw ApiException.aiFailed("AI 未返回可用摘要，请重试");
            }
            log.info("AI 摘要完成 user={} titleChars={} inputChars={} outputChars={} durationMs={}",
                    mask(key), title == null ? 0 : title.length(), input.length(), summary.length(),
                    System.currentTimeMillis() - started);
            return summary;
        } finally {
            inFlight.remove(key);
        }
    }

    private String request(ChatModel model, String userId, String title, String content) {
        var options = OpenAiChatOptions.builder()
                .temperature(0.3)
                .maxTokens(MAX_TOKENS)
                // OpenCode Go 要求稳定会话头；摘要无对话，用用户维度哈希（其他端点忽略）
                .customHeaders(Map.of("x-opencode-session", PostSummaryPrompt.sessionId(userId)));
        // 摘要不需要推理；DeepSeek 等端点据 extraBody 关闭 thinking（未知字段的端点会忽略）
        options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        return ChatClient.create(model).prompt()
                .system(PostSummaryPrompt.systemPrompt())
                .user(PostSummaryPrompt.userPrompt(title, content))
                .options(options)
                .call()
                .content();
    }

    /** 只暴露可读原因，不回显网关原文（可能含请求头 / 额度信息）。 */
    private static String friendly(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            String lower = message == null ? "" : message.toLowerCase();
            if (lower.contains("401") || lower.contains("unauthorized")
                    || lower.contains("invalid api key") || lower.contains("incorrect api key")) {
                return "AI 网关鉴权失败：请检查 AI_API_KEY";
            }
            if (lower.contains("429") || lower.contains("rate limit") || lower.contains("quota")) {
                return "AI 网关限流或额度不足，请稍后重试";
            }
            if (lower.contains("404") || lower.contains("model_not_found")) {
                return "AI 模型不存在：请检查 AI_MODEL";
            }
            if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException
                    || lower.contains("timeout") || lower.contains("timed out")) {
                return "AI 总结超时，请重试";
            }
        }
        return "AI 总结失败，请重试";
    }

    private static String mask(String userId) {
        if (userId == null || userId.isBlank()) {
            return "-";
        }
        return userId.length() <= 6 ? "***" : userId.substring(0, 6) + "***";
    }
}
