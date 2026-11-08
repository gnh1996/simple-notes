package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.AiProperties;
import cc.naihao.simplenotes.web.dto.AiChatRequest;
import cc.naihao.simplenotes.web.dto.AiChatTurn;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 主题对话编排（docs/theme-ai.md §2，无服务端记忆，会话态在前端）。
 *
 * <p>协议（SSE 事件）：{@code token} 增量文本 → {@code round} 修复轮开始 →
 * {@code done{html,css,draftId,errors,attempts}} / {@code error{message}}。
 * 每轮输出经 {@link AiThemePrompt#validate} 后才进入草稿缓存，未通过且轮数未用完则自动修。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiThemeService {

    private final AiProperties ai;
    private final AiDraftCache drafts;
    private final PreviewTokenService previewTokens;
    private final ObjectMapper objectMapper;

    private volatile OpenAiChatModel chatModel;
    private volatile String starterHtml;
    private volatile String starterCss;

    /** 单用户单并发（防双击重复扣费；不是配额限流，docs/theme-ai.md §5） */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public SseEmitter stream(AiChatRequest request, String userId) {
        if (!ai.configured()) {
            throw ApiException.badRequest("AI 未配置：请填写 AI_BASE_URL / AI_API_KEY / AI_MODEL");
        }
        if (inFlight.putIfAbsent(userId, Boolean.TRUE) != null) {
            throw ApiException.conflict("已有进行中的 AI 生成，请等待完成或中断后再试");
        }
        long timeoutMs = ai.getTimeout().toMillis() + 30_000;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> inFlight.remove(userId));
        emitter.onTimeout(() -> inFlight.remove(userId));
        emitter.onError(e -> inFlight.remove(userId));
        Thread worker = new Thread(() -> run(request, emitter, userId), "ai-theme-" + userId);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /** 草稿预览 token：复用主题预览签名（docs/theme-ai.md §4），校验在 BlogPageController 完成 */
    public PreviewTokenService.IssuedToken draftPreviewToken(String draftId) {
        if (drafts.get(draftId) == null) {
            throw ApiException.notFound("草稿不存在或已过期");
        }
        return previewTokens.issue(draftId);
    }

    private void run(AiChatRequest request, SseEmitter emitter, String userId) {
        try {
            List<Attempt> attempts = new ArrayList<>();
            String fullText = streamRound(buildPrompt(request, null), emitter, 1);
            AiThemePrompt.Extracted extracted = AiThemePrompt.extract(fullText);
            List<String> errors = AiThemePrompt.validate(
                    extracted.html(), extracted.css(), extracted.errors());
            attempts.add(new Attempt(1, errors));

            int round = 1;
            while (!errors.isEmpty() && round < ai.getMaxRounds()) {
                round++;
                send(emitter, "round", Map.of("round", round, "status", "repairing", "errors", errors));
                fullText = streamRound(buildRepairPrompt(request, fullText, errors), emitter, round);
                extracted = AiThemePrompt.extract(fullText);
                errors = AiThemePrompt.validate(extracted.html(), extracted.css(), extracted.errors());
                attempts.add(new Attempt(round, errors));
            }

            String draftId = null;
            if (errors.isEmpty()) {
                draftId = drafts.put(extracted.html(), extracted.css(), ThemeService.RUNTIME_CONTRACT);
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("html", extracted.html());
            done.put("css", extracted.css());
            done.put("contract", ThemeService.RUNTIME_CONTRACT);
            done.put("draftId", draftId);
            done.put("errors", errors);
            done.put("attempts", attempts.stream()
                    .map(attempt -> Map.of("round", attempt.round(), "errors", attempt.errors()))
                    .toList());
            send(emitter, "done", done);
            emitter.complete();
        } catch (Exception e) {
            log.warn("AI 主题生成失败 user={}: {}", mask(userId), e.getMessage());
            try {
                send(emitter, "error", Map.of("type", "error", "message", friendly(e)));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        } finally {
            inFlight.remove(userId);
        }
    }

    /** 流式跑一轮，转发 token 事件并返回累积全文 */
    private String streamRound(Prompt prompt, SseEmitter emitter, int round) throws IOException {
        StringBuilder full = new StringBuilder();
        for (ChatResponse chunk : model().stream(prompt).toIterable()) {
            if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                continue;
            }
            String delta = chunk.getResult().getOutput().getText();
            if (delta != null && !delta.isEmpty()) {
                full.append(delta);
                send(emitter, "token", Map.of("type", "token", "round", round, "delta", delta));
            }
        }
        return full.toString();
    }

    private Prompt buildPrompt(AiChatRequest request, String unused) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AiThemePrompt.systemPrompt(
                request.includeStarter() ? starterHtml() : null,
                request.includeStarter() ? starterCss() : null)));
        List<AiChatTurn> history = request.history() == null ? List.of() : request.history();
        int from = Math.max(0, history.size() - ai.getMaxHistoryTurns() * 2);
        for (AiChatTurn turn : history.subList(from, history.size())) {
            messages.add("assistant".equalsIgnoreCase(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        if (request.baseHtml() != null && !request.baseHtml().isBlank()) {
            messages.add(new UserMessage("【当前主题快照】在此基础上按我的指令修改，输出完整文件：\n```html\n"
                    + request.baseHtml() + "\n```\n"
                    + (request.baseCss() != null ? "```css\n" + request.baseCss() + "\n```" : "")));
        }
        messages.add(new UserMessage(request.prompt()));
        return new Prompt(messages, OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(0.7)
                .build());
    }

    private Prompt buildRepairPrompt(AiChatRequest request, String lastOutput, List<String> errors) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AiThemePrompt.SYSTEM_RULES));
        messages.add(new AssistantMessage(lastOutput));
        messages.add(new UserMessage(AiThemePrompt.repairPrompt(errors)));
        return new Prompt(messages, OpenAiChatOptions.builder()
                .model(ai.getModel())
                .temperature(0.3)
                .build());
    }

    private OpenAiChatModel model() {
        OpenAiChatModel current = chatModel;
        if (current == null) {
            synchronized (this) {
                current = chatModel;
                if (current == null) {
                    // 2.0 连接信息收进 Options；读超时必须落到底层 client（默认 60s，
                    // 大 prompt 首 token 常超 60s，Options.timeout 盖不住流式读，见 docs/theme-ai.md §7）。
                    // ClientOptions 不自带 httpClient 默认值，必须显式给（否则 'httpClient' is required）。
                    HttpClient httpClient = org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
                            .builder()
                            .timeout(ai.getTimeout())
                            .build();
                    // 注意两点（实测）：
                    // 1. ClientOptions.apiKey(String) 不计入 effectiveCredential，
                    //    必须用 credential(BearerTokenCredential.create(...))；
                    // 2. OpenAiChatModel.build() 还会按 options 自建 async client，
                    //    因此同步/异步两个 client 都显式传入，options 只留模型参数。
                    com.openai.core.ClientOptions clientOptions = ClientOptions.builder()
                            .baseUrl(ai.getBaseUrl())
                            .credential(com.openai.credential.BearerTokenCredential.create(ai.getApiKey()))
                            .timeout(ai.getTimeout())
                            .httpClient(httpClient)
                            .maxRetries(1)
                            .build();
                    com.openai.client.OpenAIClientAsync asyncClient =
                            new com.openai.client.OpenAIClientAsyncImpl(clientOptions);
                    OpenAIClient client = asyncClient.sync();
                    current = OpenAiChatModel.builder()
                            .openAiClient(client)
                            .openAiClientAsync(asyncClient)
                            .options(OpenAiChatOptions.builder()
                                    .model(ai.getModel())
                                    .temperature(0.7)
                                    .timeout(ai.getTimeout())
                                    .build())
                            .build();
                    chatModel = current;
                }
            }
        }
        return current;
    }

    private String starterHtml() {
        if (starterHtml == null) {
            starterHtml = readResource("blog/default-theme/theme.html");
        }
        return starterHtml;
    }

    private String starterCss() {
        if (starterCss == null) {
            starterCss = readResource("blog/default-theme/theme.css");
        }
        return starterCss;
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("缺少内置主题资源：" + path, e);
        }
    }

    private void send(SseEmitter emitter, String name, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(payload)));
    }

    private String friendly(Exception e) {
        String message = e.getMessage();
        if (message != null && (message.contains("401") || message.contains("Unauthorized"))) {
            return "AI 网关鉴权失败：请检查 AI_API_KEY";
        }
        if (message != null && message.contains("AI 未配置")) {
            return message;
        }
        String name = e.getClass().getSimpleName();
        if (name.contains("Timeout") || (message != null && message.toLowerCase().contains("timed out"))) {
            return "AI 响应超时（>" + ai.getTimeout().getSeconds() + "s）：大 prompt 首 token 慢，可重试或缩减历史";
        }
        return "AI 生成失败，请稍后重试";
    }

    private String mask(String userId) {
        if (userId == null || userId.length() <= 4) {
            return "****";
        }
        return userId.substring(0, 2) + "****" + userId.substring(userId.length() - 2);
    }

    private record Attempt(int round, List<String> errors) {
    }
}
