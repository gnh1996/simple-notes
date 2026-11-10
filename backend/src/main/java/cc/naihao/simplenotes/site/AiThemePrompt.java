package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 主题提示词与产物解析（docs/theme-ai.md §2.3/§3，v2.1）。
 *
 * <p>两种产物：
 * <ul>
 *   <li>完整主题：自由说明 + {@code <plan>} + 恰好一个 ```html 与 ```css（首轮 / 从零生成）；</li>
 *   <li>编辑补丁：单个小 JSON {@code {summary, plan, ops}}（已有当前主题时）。</li>
 * </ul>
 * 补丁不承载整篇主题，避免 JSON 转义、输出截断与上下文膨胀；应用后仍走
 * {@link ThemeTemplateValidator} 校验。本类保持纯函数，便于单测。
 */
public final class AiThemePrompt {

    static final int HTML_MAX = 64 * 1024;
    static final int CSS_MAX = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 系统规则：只写“约束”，不写具体清单。
     *
     * <p>组件 / props / slots / 类型 / 路由 / URL / CSS 变量 / 指令全部来自
     * {@link ThemeContract#promptSection()}（由 {@code pnpm sync:contract} 生成），
     * 本类不再维护手写清单。
     */
    static final String RULES = """
            你是博客主题设计师，只输出主题，不解释与主题无关的内容。
            主题 = 整页 HTML + 全部 CSS，零 JavaScript。
            用户的审美指令优先级最高：如果用户要求全新风格（卡通 / 杂志 / 极简 / 暗黑等），
            允许重写整体 DOM 结构、区块顺序与全部样式，只要满足下面的硬约束。
            不要为了省事而照抄默认主题——它只是契约用法的参考，不是设计终点。

            【硬约束（违反会被校验器拒绝）】
            1. HTML 必须包含 id="app" 根节点；禁止 <script>、v-html、javascript:、window.、document.、localStorage、fetch(、eval(、import(。
            2. 组件标签、props、events、slots、slot 作用域字段、parts 只能使用下面【组件】列出的内容。
               root slot 传内容即整体接管默认结构；default / 具名 slot 只替换内部内容。
            3. 模板指令、数据键、辅助函数、动作、表达式全局与局部变量只能使用下面列出的内容；引用其他标识符会被拒绝（v-for / v-slot 局部变量与表达式全局除外）。$event 只允许下面列出的窄形状，表单输入优先用 v-model="ui.x"。
            4. 页面分支（route.name）至少完整实现 home 与 post；缺失的分支运行时回落默认主题。
            5. Markdown 必须用 <blog-markdown :source="..."> 渲染，不得自行解析；列表用 v-for + blog-post-card；分页用 blog-pager。
            6. CSS 禁止 @import；url() 只允许 /api/media/ 与 data:；主题 CSS 只能覆盖下面列出的 .bc-* parts；体积 HTML ≤ 64KB、CSS ≤ 256KB。
            7. 覆盖样式只允许下面【CSS 变量】列出的变量；深色专用值用 [data-color-mode="dark"] 选择器。
            8. 移动端 320/375/414 无横向滚动；可点元素 ≥ 44px；明暗两套配色；中文正文 16-17px、行高 ≥ 1.7。
            9. 链接使用【URL 形态】列出的前缀；不要自行发明路由。
            """;

    // 规则部分（不含输出格式）；v2.1 由 fullSystemPrompt / patchSystemPrompt 拼输出格式。

    static final String FULL_FORMAT = """

            【输出格式：完整主题】
            - 先输出 <plan> 块：3-5 行本轮要做的事（每行以 - 开头，只写要点不展开），再用 1-3 句中文说明设计要点。
            - 然后输出两个 fenced 代码块：```html ... ``` 与 ```css ... ``` 各恰好一个。
            - 不要输出 JSON；修改时输出完整文件（不是 diff）。
            """;

    static final String PATCH_FORMAT = """

            【输出格式：编辑补丁】
            - 只输出单个 JSON 对象，不要 fences、不要前后解释文字、不要 Markdown：
              {"summary": "1-3 句中文设计要点", "plan": ["3-5 行本轮要点"], "ops": [ ... ]}
            - ops 按顺序应用，单轮最多 20 个。常用操作：
              {"op":"setCssVar","name":"--blog-radius","value":"16px"}
              {"op":"appendCss","css":".bc-post-card{padding:24px}"}
              {"op":"replace","target":"html","find":"当前主题中的唯一片段","content":"替换内容"}（target 也可以是 css）
              {"op":"replaceCss","css":"完整 CSS（仅在需要整体重写样式时）"}
              {"op":"replaceHtml","html":"完整 HTML（仅在需要整体重写结构时）"}
            - 能局部改就不要整篇重写：优先 setCssVar / appendCss；结构改动用 replace；用户要求彻底换设计时才用 replaceHtml/replaceCss。
            - 若用户要求彻底重写整篇主题且改动很大，可直接输出完整 ```html / ```css（与完整模式相同），不要塞进 JSON 字符串；否则只输出 ops JSON。
            - setCssVar 只替换首个同名变量定义（通常是 :root）；深色专用值请用 appendCss 写 [data-color-mode="dark"] { ... }。
            - 不要输出完整主题 JSON：整篇重写直接输出 fenced html/css；局部改时 html/css 只放在对应 op 的字段里。
            """;

    private static final Pattern HTML_BLOCK =
            Pattern.compile("```[ \\t]*html[ \\t]*\\R(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_BLOCK =
            Pattern.compile("```[ \\t]*css[ \\t]*\\R(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```\\s*json\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_BLOCK = Pattern.compile("<plan>([\\s\\S]*?)(?:</plan>|$)",
            Pattern.CASE_INSENSITIVE);

    private AiThemePrompt() {
    }

    /** 完整主题的 system prompt（首轮 / 无当前主题时）。 */
    public static String fullSystemPrompt(String referenceHtml, String referenceCss) {
        return RULES + ThemeContract.promptSection() + skeletonBlock()
                + FULL_FORMAT + referenceBlock(referenceHtml, referenceCss);
    }

    /** 完整模式的最小合法骨架：给模型一个可运行的起点，避免从零发明写法。 */
    private static String skeletonBlock() {
        if (ThemeContract.SKELETON_HTML.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n【最小合法骨架（起点，不是默认主题）】\n");
        text.append("```html\n").append(ThemeContract.SKELETON_HTML.strip()).append("\n```\n");
        if (!ThemeContract.SKELETON_CSS.isBlank()) {
            text.append("```css\n").append(ThemeContract.SKELETON_CSS.strip()).append("\n```\n");
        }
        text.append("在这个骨架上改设计；不要照抄默认主题，也不要引入未登记的写法。\n");
        return text.toString();
    }

    /** 编辑补丁的 system prompt（已有当前主题时）。当前主题只放这里一次，不进 ChatMemory。 */
    public static String patchSystemPrompt(String currentHtml, String currentCss) {
        return RULES + ThemeContract.promptSection() + PATCH_FORMAT + referenceBlock(currentHtml, currentCss);
    }

    private static String referenceBlock(String html, String css) {
        if ((html == null || html.isBlank()) && (css == null || css.isBlank())) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n【当前主题（只读参考，不是你的输出格式）】\n");
        if (html != null && !html.isBlank()) {
            text.append("<reference-html>\n").append(html.strip()).append("\n</reference-html>\n");
        }
        if (css != null && !css.isBlank()) {
            text.append("<reference-css>\n").append(css.strip()).append("\n</reference-css>\n");
        }
        return text.toString();
    }

    /** 完整主题修复轮：把上一版输出和错误一起喂回，要求输出完整修正版。 */
    public static String repairFullPrompt(List<String> errors, String lastOutput) {
        return "上一版未通过主题校验（如下）。请输出修正后的完整主题：先 <plan>，再恰好一个 ```html 与 ```css（不是 diff）。\n\n"
                + "【上一版输出】\n" + (lastOutput == null ? "" : lastOutput) + "\n\n"
                + "【校验错误】\n- " + String.join("\n- ", errors);
    }

    /** 补丁修复轮：只喂回上一版补丁与错误；当前主题在 system prompt 里。 */
    public static String repairPatchPrompt(List<String> errors, String lastPatchJson) {
        return "上一版补丁未通过校验（如下）。请输出修正后的补丁 JSON（不是完整主题；除非必要才用 replaceHtml/replaceCss）。\n\n"
                + "【上一版补丁】\n```json\n" + (lastPatchJson == null ? "" : lastPatchJson) + "\n```\n\n"
                + "【校验错误】\n- " + String.join("\n- ", errors);
    }

    /** 从完整主题输出中抽取 html/css/说明/plan。 */
    public static Extracted extractFull(String text) {
        List<String> errors = new ArrayList<>();
        String html = lastGroup(HTML_BLOCK, text);
        String css = lastGroup(CSS_BLOCK, text);
        String summary = summaryOf(text);
        List<String> plan = planOf(text);
        if (html == null && css == null) {
            // 兼容旧模型：仍输出 {"html": "...", "css": "..."} 的 JSON。
            LegacyDraft legacy = legacyJsonDraft(text);
            if (legacy != null) {
                html = legacy.html();
                css = legacy.css();
                if (!legacy.summary().isBlank()) {
                    summary = legacy.summary();
                }
                if (!legacy.plan().isEmpty()) {
                    plan = legacy.plan();
                }
            }
        }
        if (html == null) {
            errors.add("缺少 ```html 代码块");
            html = "";
        }
        if (css == null) {
            errors.add("缺少 ```css 代码块");
            css = "";
        }
        return new Extracted(html.strip(), css.strip(), summary, plan, errors);
    }

    /** 从补丁输出中抽取 ThemePatch；抽不到或 JSON 非法则 errors 非空。 */
    public static PatchExtracted extractPatch(String text) {
        List<String> candidates = jsonCandidates(text);
        if (candidates.isEmpty()) {
            return new PatchExtracted(null, "", List.of("缺少 JSON 补丁（请只输出单个 JSON 对象）"));
        }
        String failure = null;
        String lastJson = "";
        for (String json : candidates) {
            lastJson = json;
            try {
                ThemePatch patch = parsePatch(json);
                if (patch.ops() == null || patch.ops().isEmpty()) {
                    failure = "补丁 ops 为空";
                    continue;
                }
                return new PatchExtracted(patch, json, List.of());
            } catch (Exception e) {
                failure = "补丁 JSON 解析失败：" + brief(e);
            }
        }
        return new PatchExtracted(null, lastJson, List.of(failure == null ? "补丁 JSON 无效" : failure));
    }

    /** 体积 + id="app" 预检 + ThemeTemplateValidator 双校验，返回全部错误（为空即通过）。 */
    public static List<String> validate(String html, String css, List<String> extractErrors) {
        List<String> errors = new ArrayList<>(extractErrors);
        if (html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > HTML_MAX) {
            errors.add("主题 HTML 超出 64KB 上限");
        }
        if (css.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CSS_MAX) {
            errors.add("主题 CSS 超出 256KB 上限");
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        if (!html.contains("id=\"app\"")) {
            errors.add("html 缺少 id=\"app\" 根节点：请补上根节点后重出完整主题");
            return errors;
        }
        try {
            ThemeTemplateValidator.validateHtml(html);
        } catch (ApiException e) {
            errors.addAll(split(e.getMessage()));
        }
        try {
            ThemeTemplateValidator.validateCss(css);
        } catch (ApiException e) {
            errors.addAll(split(e.getMessage()));
        }
        if (!hasBranch(html, "home")) {
            errors.add("主题必须完整实现 home 页面分支（route.name === 'home'）");
        }
        if (!hasBranch(html, "post")) {
            errors.add("主题必须完整实现 post 页面分支（route.name === 'post'）");
        }
        return errors;
    }

    private static boolean hasBranch(String html, String name) {
        return Pattern.compile("route\\.name\\s*===?\\s*['\"]" + Pattern.quote(name) + "['\"]")
                .matcher(html)
                .find();
    }

    /**
     * ChatMemory 压缩：对话记忆只留摘要/计划/用户指令，绝不保存整篇 HTML/CSS 或补丁 JSON。
     * 当前主题由 {@link AiThemeSessionStore} 单独保存，并在 system prompt 注入一次。
     */
    public static String compactForMemory(String text, String role) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String summary = jsonSummaryOf(text);
        List<String> plan = jsonPlanOf(text);
        if (summary.isBlank()) {
            summary = summaryOf(text);
        }
        if (plan.isEmpty()) {
            plan = planOf(text);
        }
        String prefix = "user".equals(role) ? "[用户指令] " : "[本轮助手] ";
        StringBuilder compact = new StringBuilder(prefix).append(summary);
        if (!plan.isEmpty()) {
            compact.append(" [计划：").append(String.join("；", plan)).append("]");
        }
        if (compact.length() > 1200) {
            return compact.substring(0, 1200) + "…";
        }
        return compact.toString();
    }

    /** 说明文字：优先 JSON summary，再去掉 plan/fence 取前 500 字符。 */
    public static String summaryOf(String text) {
        String jsonSummary = jsonSummaryOf(text);
        if (!jsonSummary.isBlank()) {
            return jsonSummary;
        }
        String prose = text == null ? "" : text;
        prose = PLAN_BLOCK.matcher(prose).replaceAll(" ");
        prose = HTML_BLOCK.matcher(prose).replaceAll(" ");
        prose = CSS_BLOCK.matcher(prose).replaceAll(" ");
        prose = JSON_BLOCK.matcher(prose).replaceAll(" ");
        prose = prose.replaceAll("\\s+", " ").strip();
        if (prose.isEmpty()) {
            return "已生成主题";
        }
        return prose.length() > 500 ? prose.substring(0, 500) + "…" : prose;
    }

    /** 计划：优先 <plan> 文本块，其次 JSON plan。 */
    public static List<String> planOf(String text) {
        List<String> fromJson = jsonPlanOf(text);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        if (text == null) {
            return List.of();
        }
        Matcher matcher = PLAN_BLOCK.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> plan = new ArrayList<>();
        for (String line : matcher.group(1).split("\n")) {
            String item = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").trim();
            if (!item.isEmpty()) {
                plan.add(item);
            }
            if (plan.size() >= 8) {
                break;
            }
        }
        return plan;
    }

    private static ThemePatch parsePatch(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("不是 JSON 对象");
        }
        String summary = text(root.get("summary"));
        List<String> plan = strings(root.get("plan"));
        JsonNode opsNode = root.get("ops");
        if (opsNode == null || !opsNode.isArray()) {
            throw new IllegalArgumentException("缺少 ops 数组");
        }
        List<ThemePatch.ThemeOp> ops = new ArrayList<>();
        for (int i = 0; i < opsNode.size(); i++) {
            JsonNode node = opsNode.get(i);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("ops[" + i + "] 不是对象");
            }
            ops.add(new ThemePatch.ThemeOp(
                    text(node.get("op")),
                    text(node.get("name")),
                    text(node.get("value")),
                    text(node.get("css")),
                    text(node.get("html")),
                    text(node.get("target")),
                    text(node.get("find")),
                    text(node.get("content"))));
        }
        return new ThemePatch(summary, plan, ops);
    }

    private static String jsonSummaryOf(String text) {
        for (String json : jsonCandidates(text)) {
            try {
                JsonNode root = MAPPER.readTree(json);
                String summary = text(root.get("summary"));
                if (!summary.isBlank()) {
                    return summary;
                }
            } catch (Exception ignored) {
                // 不是合法 JSON 就继续
            }
        }
        return "";
    }

    private static List<String> jsonPlanOf(String text) {
        for (String json : jsonCandidates(text)) {
            try {
                JsonNode root = MAPPER.readTree(json);
                List<String> plan = strings(root.get("plan"));
                if (!plan.isEmpty()) {
                    return plan;
                }
            } catch (Exception ignored) {
                // 不是合法 JSON 就继续
            }
        }
        return List.of();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isString() ? node.asString() : node.toString();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String value = text(node.get(i)).strip();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        } else if (node.isString()) {
            String value = node.asString().strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /** 按序收集候选 JSON：fence 块、全文、以及每个 `{` 起的括号配平对象。 */
    private static List<String> jsonCandidates(String text) {
        Set<String> candidates = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        Matcher fenced = JSON_BLOCK.matcher(text);
        while (fenced.find()) {
            addCandidate(candidates, fenced.group(1));
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) {
            addCandidate(candidates, trimmed);
        }
        int scanned = 0;
        for (int i = 0; i < text.length() && scanned < 8; i++) {
            if (text.charAt(i) != '{') {
                continue;
            }
            String object = balancedAt(text, i);
            if (object != null) {
                addCandidate(candidates, object);
                scanned++;
            }
        }
        return List.copyOf(candidates);
    }

    private static void addCandidate(Set<String> candidates, String value) {
        String trimmed = value == null ? "" : value.strip();
        if (!trimmed.isEmpty()) {
            candidates.add(trimmed);
        }
    }

    private static String balancedAt(String text, int start) {
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static LegacyDraft legacyJsonDraft(String text) {
        for (String json : jsonCandidates(text)) {
            try {
                JsonNode root = MAPPER.readTree(json);
                String html = text(root.get("html"));
                String css = text(root.get("css"));
                if (!html.isBlank() && !css.isBlank()) {
                    return new LegacyDraft(
                            html, css, text(root.get("summary")), strings(root.get("plan")));
                }
            } catch (Exception ignored) {
                // 继续
            }
        }
        return null;
    }

    private static String lastGroup(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    private static List<String> split(String message) {
        List<String> lines = new ArrayList<>();
        for (String line : message.split("\n")) {
            String trimmed = line.replaceFirst("^[\\-•\\s]+", "").trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("主题 HTML 校验失败")) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            lines.add(message);
        }
        return lines;
    }

    private record LegacyDraft(String html, String css, String summary, List<String> plan) {
    }

    public record Extracted(String html, String css, String summary, List<String> plan, List<String> errors) {
    }

    public record PatchExtracted(ThemePatch patch, String json, List<String> errors) {
    }
}
