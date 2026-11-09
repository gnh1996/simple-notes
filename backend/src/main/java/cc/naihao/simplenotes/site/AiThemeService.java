package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.AiProperties;
import cc.naihao.simplenotes.web.dto.AiChatRequest;
import cc.naihao.simplenotes.web.dto.AiChatTurn;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 主题对话编排（docs/theme-ai.md，v2.1）。
 *
 * <p>产物协议：首轮/无当前主题 → 完整 html/css；已有当前主题 → 小 JSON 补丁
 * {@code {summary, plan, ops}}，后端应用到当前主题后再校验。当前主题保存在
 * {@link AiThemeSessionStore}，ChatMemory 只存压缩后的对话摘要，避免整篇主题反复进出上下文。
 *
 * <p>SSE：{@code token / round / done / error}；{@code done} 额外带
 * {@code mode / finishReason / truncated / outputChars / usage / errorKind} 方便排查。
 */
@Service
@Slf4j
public class AiThemeService {

    private enum Mode {
        FULL, PATCH
    }

    private final AiProperties ai;
    /**
     * 缺 key 时 {@code AiEnvironmentPostProcessor} 排除 OpenAI/ChatClient 装配，
     * 此处 bean 缺席即“未配置”（fail-soft）。
     */
    private final ObjectProvider<ChatClient> chatClients;
    private final ChatMemory chatMemory;
    private final ExecutorService aiThemeExecutor;
    private final AiDraftCache drafts;
    private final AiThemeSessionStore sessions;
    private final PreviewTokenService previewTokens;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.model:}")
    private String modelName;

    private volatile String starterHtml;
    private volatile String starterCss;

    /** 单用户单并发（防双击重复扣费；不是配额限流，docs/theme-ai.md §2.2） */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public AiThemeService(
            AiProperties ai,
            ObjectProvider<ChatClient> chatClients,
            ChatMemory chatMemory,
            ExecutorService aiThemeExecutor,
            AiDraftCache drafts,
            AiThemeSessionStore sessions,
            PreviewTokenService previewTokens,
            ObjectMapper objectMapper) {
        this.ai = ai;
        this.chatClients = chatClients;
        this.chatMemory = chatMemory;
        this.aiThemeExecutor = aiThemeExecutor;
        this.drafts = drafts;
        this.sessions = sessions;
        this.previewTokens = previewTokens;
        this.objectMapper = objectMapper;
    }

    public SseEmitter stream(AiChatRequest request, String userId) {
        ChatClient chatClient = chatClients.getIfAvailable();
        if (chatClient == null) {
            log.info("AI 主题请求被拒：ChatClient 未装配（检查 AI_BASE_URL / AI_API_KEY / AI_MODEL） user={} session={}",
                    mask(userId), shortId(request.sessionId()));
            throw ApiException.badRequest("AI 未配置：请填写 AI_BASE_URL / AI_API_KEY / AI_MODEL");
        }
        if (inFlight.putIfAbsent(userId, Boolean.TRUE) != null) {
            log.info("AI 主题请求被拒：已有进行中 user={} session={}", mask(userId), shortId(request.sessionId()));
            throw ApiException.conflict("已有进行中的 AI 生成，请等待完成或中断后再试");
        }
        long timeoutMs = ai.getTimeout().toMillis() * ai.getMaxRounds() + 30_000;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Future<?> task = aiThemeExecutor.submit(() -> run(chatClient, request, emitter, userId));
        emitter.onCompletion(() -> {
            task.cancel(true);
            inFlight.remove(userId);
            log.debug("AI SSE 完成 user={} session={}", mask(userId), shortId(request.sessionId()));
        });
        emitter.onTimeout(() -> {
            task.cancel(true);
            inFlight.remove(userId);
            log.warn("AI SSE 超时 user={} session={} timeoutMs={}", mask(userId), shortId(request.sessionId()), timeoutMs);
        });
        emitter.onError(e -> {
            task.cancel(true);
            inFlight.remove(userId);
            log.info("AI SSE 断开 user={} session={} reason={}", mask(userId), shortId(request.sessionId()), briefChain(e));
        });
        return emitter;
    }

    /** 草稿预览 token：复用主题预览签名（docs/theme-ai.md §4），校验在 BlogPageController 完成 */
    public PreviewTokenService.IssuedToken draftPreviewToken(String draftId) {
        if (drafts.get(draftId) == null) {
            throw ApiException.notFound("草稿不存在或已过期");
        }
        return previewTokens.issue(draftId);
    }

    private void run(ChatClient chatClient, AiChatRequest request, SseEmitter emitter, String userId) {
        String conversationId = conversationId(userId, request.themeId(), request.sessionId());
        long started = System.currentTimeMillis();
        AiThemeSessionStore.ThemeState current = sessions.get(conversationId);
        boolean basePresent = !isBlank(request.baseHtml());
        Mode mode = current == null && !basePresent && !request.includeStarter() ? Mode.FULL : Mode.PATCH;
        try {
            boolean firstRound = chatMemory.get(conversationId).isEmpty();
            seedFromHistory(conversationId, request.history());

            // 有当前主题 / 前端 base / starter 时一律走补丁：不再让模型从零输出整篇主题，
            // 只有“没有主题且没勾 starter”才进入完整生成（最容易触发输出截断的路径）。
            String initialHtml;
            String initialCss;
            if (current != null) {
                initialHtml = current.html();
                initialCss = current.css();
            } else if (basePresent) {
                initialHtml = request.baseHtml();
                initialCss = request.baseCss() == null ? "" : request.baseCss();
            } else if (request.includeStarter()) {
                initialHtml = starterHtml();
                initialCss = starterCss();
            } else {
                initialHtml = "";
                initialCss = "";
            }
            mode = initialHtml.isBlank() ? Mode.FULL : Mode.PATCH;
            boolean starterAsBase = current == null && !basePresent && request.includeStarter();

            log.info("AI 主题开始 user={} session={} theme={} mode={} model={} firstRound={} history={} "
                            + "baseHtmlChars={} baseCssChars={} starterAsBase={} promptChars={} maxTokens={} thinking={} reasoningEffort={}",
                    mask(userId), shortId(request.sessionId()), shortId(request.themeId()), mode,
                    modelName == null || modelName.isBlank() ? "?" : modelName, firstRound,
                    request.history() == null ? 0 : request.history().size(),
                    chars(request.baseHtml()), chars(request.baseCss()), starterAsBase, chars(request.prompt()),
                    ai.getMaxTokens(), blankToDash(ai.getThinking()), blankToDash(ai.getReasoningEffort()));

            String baseHtml = initialHtml;
            String baseCss = initialCss;
            List<Attempt> attempts = new ArrayList<>();
            List<String> errors = List.of();
            String html = baseHtml;
            String css = baseCss;
            String summary = current == null ? "" : current.summary();
            List<String> plan = current == null ? List.of() : current.plan();
            String lastOutput = "";
            String lastPatchJson = "";
            String finishReason = null;
            Map<String, Integer> usage = Map.of();
            boolean parseFailed = false;
            int reasoningChars = 0;
            int round = 0;

            while (true) {
                round++;
                String system = mode == Mode.FULL
                        ? AiThemePrompt.fullSystemPrompt(null, null)
                        : AiThemePrompt.patchSystemPrompt(baseHtml, baseCss);
                String userText = round == 1
                        ? request.prompt()
                        : mode == Mode.FULL
                                ? AiThemePrompt.repairFullPrompt(errors, lastOutput)
                                : AiThemePrompt.repairPatchPrompt(errors, lastPatchJson);

                log.info("AI 第 {} 轮开始 session={} mode={} systemChars={} userChars={} temperature={} maxTokens={} "
                                + "thinking={} reasoningEffort={}",
                        round, shortId(request.sessionId()), mode, chars(system), chars(userText),
                        round == 1 ? 0.7 : 0.3, ai.getMaxTokens(),
                        blankToDash(ai.getThinking()), blankToDash(ai.getReasoningEffort()));
                dumpPrompt(conversationId, round, system, userText);

                RoundResult result = streamRound(chatClient, conversationId, system, userText,
                        round == 1 ? 0.7 : 0.3, emitter, round);
                lastOutput = result.text();
                finishReason = result.finishReason();
                usage = result.usage();
                reasoningChars = result.reasoningChars();

                Outcome outcome = evaluate(mode, result.text(), baseHtml, baseCss);
                html = outcome.html();
                css = outcome.css();
                summary = outcome.summary();
                plan = outcome.plan();
                errors = outcome.errors();
                parseFailed = outcome.parseFailed();
                if (!outcome.patchJson().isBlank()) {
                    lastPatchJson = outcome.patchJson();
                }
                attempts.add(new Attempt(round, errors));

                log.info("AI 第 {} 轮结果 session={} mode={} parseFailed={} htmlChars={} cssChars={} "
                                + "summaryChars={} plan={} reasoningChars={} errors={}",
                        round, shortId(request.sessionId()), mode, parseFailed, chars(html), chars(css),
                        chars(summary), plan.size(), reasoningChars, errors);
                if (isTruncated(finishReason) && !errors.isEmpty()) {
                    errors = new ArrayList<>(errors);
                    errors.add(truncationHint(finishReason, lastOutput.length(), reasoningChars));
                    attempts.set(attempts.size() - 1, new Attempt(round, errors));
                    log.warn("AI 第 {} 轮输出被截断，停止自动修复 session={} finishReason={} outputChars={} "
                                    + "reasoningChars={} maxTokens={}",
                            round, shortId(request.sessionId()), finishReason, lastOutput.length(), reasoningChars,
                            ai.getMaxTokens());
                    break;
                }
                if (errors.isEmpty() || round >= ai.getMaxRounds()) {
                    break;
                }
                log.info("AI 第 {} 轮校验失败，进入修复 session={} errors={}", round, shortId(request.sessionId()), errors);
                send(emitter, "round", Map.of("round", round + 1, "status", "repairing", "errors", errors));
            }

            boolean valid = errors.isEmpty();
            String draftId = null;
            if (valid) {
                sessions.put(conversationId, html, css, ThemeService.RUNTIME_CONTRACT, summary, plan);
                draftId = drafts.put(html, css, ThemeService.RUNTIME_CONTRACT);
            }
            boolean truncated = isTruncated(finishReason);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("html", html);
            done.put("css", css);
            done.put("summary", summary);
            done.put("plan", plan);
            done.put("contract", ThemeService.RUNTIME_CONTRACT);
            done.put("draftId", draftId);
            done.put("errors", errors);
            done.put("attempts", attempts.stream()
                    .map(attempt -> Map.of("round", attempt.round(), "errors", attempt.errors()))
                    .toList());
            done.put("mode", mode.name().toLowerCase());
            done.put("finishReason", finishReason == null ? "" : finishReason);
            done.put("truncated", truncated);
            done.put("outputChars", lastOutput.length());
            done.put("reasoningChars", reasoningChars);
            done.put("usage", usage);
            done.put("errorKind", errors.isEmpty() ? "none" : (truncated ? "truncated" : (parseFailed ? "parse" : "validate")));
            send(emitter, "done", done);
            emitter.complete();
            log.info("AI 主题完成 user={} session={} mode={} valid={} attempts={} draftId={} durationMs={} "
                            + "htmlChars={} cssChars={} finishReason={} truncated={} reasoningChars={} usage={}",
                    mask(userId), shortId(request.sessionId()), mode, valid, attempts.size(), draftId,
                    System.currentTimeMillis() - started, chars(html), chars(css), finishReason, truncated,
                    reasoningChars, usage);
        } catch (Exception e) {
            if (isCancellation(e)) {
                log.info("AI 主题取消 user={} session={} durationMs={} reason={}",
                        mask(userId), shortId(request.sessionId()), System.currentTimeMillis() - started,
                        briefChain(e));
                return;
            }
            log.warn("AI 主题生成失败 user={} session={} mode={} durationMs={}",
                    mask(userId), shortId(request.sessionId()), mode, System.currentTimeMillis() - started, e);
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

    private Outcome evaluate(Mode mode, String text, String baseHtml, String baseCss) {
        if (mode == Mode.FULL) {
            AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull(text);
            List<String> errors = AiThemePrompt.validate(extracted.html(), extracted.css(), extracted.errors());
            return new Outcome(extracted.html(), extracted.css(), extracted.summary(), extracted.plan(),
                    errors, !extracted.errors().isEmpty(), "");
        }
        AiThemePrompt.PatchExtracted extracted = AiThemePrompt.extractPatch(text);
        if (!extracted.errors().isEmpty()) {
            // 兜底：补丁模式下模型仍输出完整 html/css（弱模型常见），按整体替换处理，避免直接失败。
            AiThemePrompt.Extracted full = AiThemePrompt.extractFull(text);
            if (full.errors().isEmpty()) {
                List<String> fullErrors = AiThemePrompt.validate(full.html(), full.css(), List.of());
                log.info("AI 补丁模式收到完整主题，按整体替换处理 htmlChars={} cssChars={} errors={}",
                        chars(full.html()), chars(full.css()), fullErrors);
                return new Outcome(full.html(), full.css(), full.summary(), full.plan(),
                        fullErrors, false, "");
            }
            return new Outcome(baseHtml, baseCss, "", List.of(), extracted.errors(), true, extracted.json());
        }
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(baseHtml, baseCss, extracted.patch());
        List<String> errors = new ArrayList<>(applied.errors());
        if (errors.isEmpty()) {
            errors = AiThemePrompt.validate(applied.html(), applied.css(), List.of());
        }
        log.info("AI 补丁应用 ops={} applied={} errors={}", extracted.patch().ops().size(), applied.applied(), errors);
        return new Outcome(applied.html(), applied.css(), extracted.patch().summary(), extracted.patch().plan(),
                errors, false, extracted.json());
    }

    /**
     * 调试用：把本轮实际拼装的 system / user 与当前 ChatMemory 写入目录。
     *
     * <p>这是确认“模型到底看到了什么”的最短路径：system 含契约投影、输出格式与当前主题，
     * user 是本轮指令或修复指令，memory 是压缩后的历史。默认关闭（AI_PROMPT_DUMP_DIR 为空）。
     */
    private void dumpPrompt(String conversationId, int round, String system, String userText) {
        String dir = ai.getPromptDumpDir();
        if (isBlank(dir)) {
            return;
        }
        try {
            Path base = Path.of(dir.strip());
            Files.createDirectories(base);
            String prefix = "ai-prompt-" + System.currentTimeMillis() + "-r" + round + "-"
                    + safeName(conversationId);
            Files.writeString(base.resolve(prefix + "-system.txt"),
                    system == null ? "" : system, StandardCharsets.UTF_8);
            Files.writeString(base.resolve(prefix + "-user.txt"),
                    userText == null ? "" : userText, StandardCharsets.UTF_8);

            StringBuilder memoryText = new StringBuilder()
                    .append("# ChatMemory before round ").append(round).append("\n\n");
            for (Message message : chatMemory.get(conversationId)) {
                memoryText.append('[').append(message.getMessageType()).append("]\n")
                        .append(message.getText()).append("\n\n");
            }
            Files.writeString(base.resolve(prefix + "-memory.txt"),
                    memoryText.toString(), StandardCharsets.UTF_8);
            log.info("AI prompt 已写入 {}（round={} session={}）",
                    base.toAbsolutePath(), round, shortId(conversationId));
        } catch (Exception e) {
            // 调试能力不能影响生成
            log.warn("AI prompt dump 失败 dir={} session={}", dir, shortId(conversationId), e);
        }
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "session";
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private RoundResult streamRound(ChatClient chatClient, String conversationId, String system, String userText,
            double temperature, SseEmitter emitter, int round) throws IOException {
        var options = OpenAiChatOptions.builder()
                .temperature(temperature)
                // OpenCode Go 现在强制要求稳定会话头，用于路由与 prompt caching；
                // 其他 OpenAI-compatible 端点会忽略该头。用会话哈希，避免把原始 id 发出去。
                .customHeaders(Map.of("x-opencode-session", openCodeSessionId(conversationId)));
        if (ai.getMaxTokens() > 0) {
            // max_tokens 在 OpenAI-compatible 网关上支持面更广；max_completion_tokens 部分端点会 400。
            options.maxTokens(ai.getMaxTokens());
        }
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (!isBlank(ai.getReasoningEffort())) {
            // 用 extraBody 透传，兼容 DeepSeek 的 none/max 等 OpenAI SDK 枚举之外的取值。
            extraBody.put("reasoning_effort", ai.getReasoningEffort().strip());
        }
        if ("disabled".equalsIgnoreCase(ai.getThinking())) {
            extraBody.put("thinking", Map.of("type", "disabled"));
        } else if ("enabled".equalsIgnoreCase(ai.getThinking())) {
            extraBody.put("thinking", Map.of("type", "enabled"));
        }
        if (!extraBody.isEmpty()) {
            options.extraBody(extraBody);
        }
        StringBuilder full = new StringBuilder();
        ChatResponse lastAny = null;
        ChatResponse lastWithResult = null;
        int reasoningChars = 0;
        long started = System.currentTimeMillis();

        Iterable<ChatResponse> chunks = chatClient.prompt()
                .system(system)
                .user(userText)
                .options(options)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatResponse()
                .toIterable();
        for (ChatResponse chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            lastAny = chunk;
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                continue;
            }
            lastWithResult = chunk;
            reasoningChars = Math.max(reasoningChars, reasoningOf(chunk).length());
            String delta = chunk.getResult().getOutput().getText();
            if (delta != null && !delta.isEmpty()) {
                full.append(delta);
                send(emitter, "token", Map.of("type", "token", "round", round, "delta", delta));
            }
        }

        // OpenAI 流式最后一个 chunk（usage）通常没有 result；finishReason 取最后一个有 result 的 chunk。
        String finishReason = finishReason(lastWithResult);
        Map<String, Integer> usage = usageOf(lastAny != null ? lastAny : lastWithResult);
        log.info("AI 第 {} 轮完成 durationMs={} outputChars={} reasoningChars={} finishReason={} usage={}",
                round, System.currentTimeMillis() - started, full.length(), reasoningChars, finishReason, usage);
        if (log.isDebugEnabled()) {
            log.debug("AI 第 {} 轮输出头: {}", round, head(full.toString(), 600));
            log.debug("AI 第 {} 轮输出尾: {}", round, tail(full.toString(), 600));
        }
        return new RoundResult(full.toString(), finishReason, usage, reasoningChars);
    }

    /** reasoning 模型（DeepSeek/Qwen/OpenAI o 系列）把思考内容放 metadata，不计入 getText()。 */
    private static String reasoningOf(ChatResponse chunk) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        Object value = chunk.getResult().getOutput().getMetadata().get("reasoningContent");
        if (value == null) {
            value = chunk.getResult().getOutput().getMetadata().get("reasoning");
        }
        return value instanceof String text ? text : "";
    }

    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        String reason = response.getResult().getMetadata().getFinishReason();
        return reason == null || reason.isBlank() ? null : reason;
    }

    private static Map<String, Integer> usageOf(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return Map.of();
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        putUsage(values, "promptTokens", usage.getPromptTokens());
        putUsage(values, "completionTokens", usage.getCompletionTokens());
        putUsage(values, "totalTokens", usage.getTotalTokens());
        return values;
    }

    private static void putUsage(Map<String, Integer> values, String key, Integer value) {
        if (value != null && value > 0) {
            values.put(key, value);
        }
    }

    private static boolean isTruncated(String finishReason) {
        if (finishReason == null) {
            return false;
        }
        String lower = finishReason.toLowerCase();
        return lower.contains("length") || lower.contains("max_token") || lower.contains("max_output");
    }

    private String truncationHint(String finishReason, int outputChars, int reasoningChars) {
        String budget = ai.getMaxTokens() > 0 ? String.valueOf(ai.getMaxTokens()) : "端点默认";
        StringBuilder hint = new StringBuilder("输出被截断（finishReason=")
                .append(finishReason)
                .append("，AI_MAX_TOKENS=")
                .append(budget)
                .append("，正文 ")
                .append(outputChars)
                .append(" 字符");
        if (reasoningChars > 0) {
            hint.append("，reasoning ").append(reasoningChars).append(" 字符");
        }
        hint.append("）。");
        if (outputChars == 0 && reasoningChars > 0) {
            hint.append("模型把 token 全用在 reasoning，未产生正文；请换非推理模型/关闭 thinking，或提高输出上限。");
        } else if (outputChars == 0) {
            hint.append("模型未产生正文；请检查 AI_MODEL 是否支持当前 prompt/参数，或换用非推理模型。");
        } else {
            hint.append("请提高 AI_MAX_TOKENS、换用非推理模型/关闭 thinking，或缩小主题范围；本轮不再自动修复。");
        }
        return hint.toString();
    }

    private void seedFromHistory(String conversationId, List<AiChatTurn> history) {
        if (!chatMemory.get(conversationId).isEmpty()) {
            return;
        }
        if (history == null || history.isEmpty()) {
            log.debug("AI 会话种子为空 session={}", shortId(conversationId));
            return;
        }
        int from = Math.max(0, history.size() - ai.getMaxHistoryTurns() * 2);
        List<Message> seed = new ArrayList<>();
        for (AiChatTurn turn : history.subList(from, history.size())) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            seed.add("assistant".equalsIgnoreCase(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        if (!seed.isEmpty()) {
            chatMemory.add(conversationId, seed);
            log.info("AI 会话种子写入 session={} messages={}", shortId(conversationId), seed.size());
        }
    }

    private static String conversationId(String userId, String themeId, String sessionId) {
        String session = sanitize(sessionId, "default", 64);
        String theme = sanitize(themeId, "none", 36);
        return "ai-theme:" + userId + ":" + theme + ":" + session;
    }

    /** OpenCode Go 路由提示：按会话稳定、不暴露原始 id；其他 OpenAI-compatible 端点会忽略。 */
    private static String openCodeSessionId(String conversationId) {
        return UUID.nameUUIDFromBytes(conversationId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sanitize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.strip().replaceAll("[^A-Za-z0-9_-]", "-");
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
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
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    /** 客户端断开（SSE 写失败）：不是模型错误，日志按取消处理。 */
    private static final class ClientDisconnectedException extends IOException {
        ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }

    private String friendly(Exception e) {
        if (chainContains(e, "401", "Unauthorized", "Invalid API key", "Incorrect API key")) {
            return "AI 网关鉴权失败：请检查 AI_API_KEY";
        }
        if (chainContains(e, "429", "rate limit", "insufficient_quota", "quota")) {
            return "AI 网关限流或额度不足：请稍后重试，或检查账户余额";
        }
        if (chainContains(e, "404", "model_not_found", "does not exist", "not found")) {
            return "AI 模型不存在：请检查 AI_MODEL 与网关支持列表";
        }
        if (chainContains(e, "max_tokens", "max_completion_tokens", "max output tokens")) {
            return "AI 端点不接受 max_tokens：请把 AI_MAX_TOKENS 设为 0 后重试";
        }
        if (chainContains(e, "400", "invalid_request_error", "invalid request")) {
            return "AI 请求被端点拒绝：请检查 AI_MODEL、AI_MAX_TOKENS 与网关参数兼容性";
        }
        if (chainContains(e, "AI 未配置")) {
            return e.getMessage();
        }
        if (isTimeout(e)) {
            return "AI 响应超时（单轮限时 " + ai.getTimeout().getSeconds()
                    + "s）：可调大 AI_TIMEOUT 后重试，或缩小本轮改动范围";
        }
        return "AI 生成失败，请稍后重试";
    }

    private static boolean chainContains(Exception e, String... fragments) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                for (String fragment : fragments) {
                    if (lower.contains(fragment.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isTimeout(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.InterruptedIOException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            if (name.contains("Timeout")) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCancellation(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof java.util.concurrent.CancellationException
                    || t instanceof ClientDisconnectedException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.toLowerCase().contains("interrupted")) {
                return true;
            }
        }
        return false;
    }

    private static String briefChain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                String single = t.getMessage().replaceAll("\\s+", " ").trim();
                return single.length() > 300 ? single.substring(0, 300) + "…" : single;
            }
        }
        return e == null ? "" : e.getClass().getSimpleName();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private static int chars(String value) {
        return value == null ? 0 : value.length();
    }

    private static String head(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String tail(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : "…" + value.substring(value.length() - max);
    }

    private String mask(String userId) {
        if (userId == null || userId.length() <= 4) {
            return "****";
        }
        return userId.substring(0, 2) + "****" + userId.substring(userId.length() - 2);
    }

    private static String shortId(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= 10 ? value : value.substring(0, 10) + "…";
    }

    private record Attempt(int round, List<String> errors) {
    }

    private record RoundResult(String text, String finishReason, Map<String, Integer> usage, int reasoningChars) {
    }

    private record Outcome(
            String html,
            String css,
            String summary,
            List<String> plan,
            List<String> errors,
            boolean parseFailed,
            String patchJson) {
    }
}
