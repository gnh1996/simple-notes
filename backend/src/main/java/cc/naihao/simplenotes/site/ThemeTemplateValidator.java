package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主题模板静态校验（docs/blog-runtime-theme.md §8）。
 *
 * <p>后端在保存主题版本时执行与前端 {@code theme-contract.mjs} 同口径的保守扫描：
 * 组件标签、指令、插值 / 指令表达式中的自由标识符必须来自契约。Vue 的完整表达式语法
 * 不在 Java 侧重实现，因此这里采用与构建期扫描器相同的正则规则；任何无法确认的写法
 * 都应被拒绝，而不是静默放进运行时。
 *
 * <p>契约清单与 {@code frontend/apps/blog/src/contract.ts} 同步；前端 {@code pnpm check:contract}
 * 负责 runtime 实际暴露面与登记面一致，本类负责“保存时”拦截。
 */
public final class ThemeTemplateValidator {

    /** 组件 / 指令 / 模板键全部来自生成的契约投影，禁止在本类手写清单。 */
    private static final Set<String> COMPONENTS = ThemeContract.COMPONENTS;
    private static final Set<String> DIRECTIVES = ThemeContract.DIRECTIVES;
    private static final Set<String> TEMPLATE_KEYS = ThemeContract.TEMPLATE_KEYS;

    private static final Set<String> JS_KEYWORDS = Set.of(
            "true", "false", "null", "undefined", "in", "of", "new", "typeof", "instanceof",
            "void", "delete", "return", "if", "else", "this", "NaN", "Infinity");

    private static final Pattern COMPONENT_TAG = Pattern.compile("<\\s*(blog-[a-z-]+)");
    private static final Pattern DIRECTIVE = Pattern.compile("\\s(v-[a-z-]+)");
    private static final Pattern INTERPOLATION = Pattern.compile("\\{\\{([\\s\\S]*?)}}");
    private static final Pattern BINDING = Pattern.compile(
            "\\s([:@][A-Za-z0-9_.:\\[\\]-]+|v-(?:bind|on|if|else-if|for|show|model|text|once)"
                    + "[A-Za-z0-9_.:\\[\\]-]*)=\"([^\"]*)\"");
    private static final Pattern V_FOR = Pattern.compile("v-for=\"\\(?([^)\"]*?)\\)?\\s+(?:in|of)\\s+");
    private static final Pattern SLOT_SCOPE = Pattern.compile(
            "(?:#[\\w-]*|v-slot(?::[\\w-]+)?)=\"([^\"]*)\"");
    private static final Pattern ARROW_PARAMS = Pattern.compile(
            "(?:\\(([^()]*)\\)|([A-Za-z_$][\\w$]*))\\s*=>");
    private static final Pattern STRING_LITERAL = Pattern.compile(
            "'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"|`(?:\\\\.|[^`\\\\])*`");
    private static final Pattern FREE_IDENTIFIER = Pattern.compile(
            "(?<![\\w$.])([A-Za-z_$][\\w$]*)");
    private static final Pattern CSS_URL = Pattern.compile("url\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPONENT_WITH_ATTRS = Pattern.compile("<\\s*(blog-[a-z-]+)([^>]*)>");
    private static final Pattern SLOT_USAGE = Pattern.compile(
            "(?:#([\\w-]+)|v-slot(?::([\\w-]+))?)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern EVENT_ATTR = Pattern.compile("(?:^|\\s)(?:@|v-on:)([\\w-]+)");
    private static final Pattern CSS_CLASS = Pattern.compile("\\.(bc-[a-z0-9-]+)");
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CSS_STRING = Pattern.compile("'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"");

    /**
     * HTML 实体归一（docs/blog-runtime-theme.md §8）：解码回原文字符，与前端
     * {@code theme.ts} 的 {@code normalizeEntities} 同口径。运行时加载时校验吃的是
     * {@code app.outerHTML}（浏览器序列化产物，{@code &&} 会被转写为 {@code &amp;&amp;}，
     * 连箭头函数 {@code =>} 都会变成 {@code =&gt;}），直接扫描会把 amp/lt/gt
     * 误判为未登记标识符、把箭头参数冲掉，导致回落默认主题。
     * 解码顺序固定（{@code &amp;} 必须最后），避免 {@code &amp;lt;} 被级联解码两次；
     * 只处理序列化会产生的六种实体。
     */
    private static final Pattern HTML_ENTITY_LT = Pattern.compile("&lt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_GT = Pattern.compile("&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_QUOT = Pattern.compile("&quot;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_APOS =
            Pattern.compile("&#39;|&#x27;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_NBSP = Pattern.compile("&nbsp;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_AMP = Pattern.compile("&amp;", Pattern.CASE_INSENSITIVE);

    private static String normalizeEntities(String html) {
        String source = html == null ? "" : html;
        source = HTML_ENTITY_LT.matcher(source).replaceAll("<");
        source = HTML_ENTITY_GT.matcher(source).replaceAll(">");
        source = HTML_ENTITY_QUOT.matcher(source).replaceAll("\"");
        source = HTML_ENTITY_APOS.matcher(source).replaceAll("'");
        source = HTML_ENTITY_NBSP.matcher(source).replaceAll(" ");
        return HTML_ENTITY_AMP.matcher(source).replaceAll("&");
    }

    private ThemeTemplateValidator() {
    }

    public static void validateHtml(String html) {
        // 先做实体归一（见 normalizeEntities）：保存时吃原文、运行时吃 outerHTML，
        // 归一（解码）后两处口径一致，合法的 && / < / > 不会被误判。
        String source = normalizeEntities(html);
        String lower = source.toLowerCase(Locale.ROOT);
        if (!lower.contains("id=\"app\"") && !lower.contains("id='app'")) {
            throw ApiException.badRequest("主题必须包含 id=\"app\" 根节点");
        }
        for (String pattern : List.of(
                "<script", "v-html", "javascript:", "window.", "document.",
                "localstorage", "fetch(", "eval(", "import(")) {
            if (lower.contains(pattern)) {
                throw ApiException.badRequest("主题 HTML 含禁用内容：" + pattern);
            }
        }

        List<String> errors = new ArrayList<>();

        for (Matcher matcher = COMPONENT_TAG.matcher(source); matcher.find(); ) {
            String name = matcher.group(1);
            if (!COMPONENTS.contains(name)) {
                errors.add(lineOf(source, matcher.start()) + " 使用了未登记的组件 <" + name + ">");
            }
        }

        for (Matcher matcher = DIRECTIVE.matcher(source); matcher.find(); ) {
            String name = matcher.group(1);
            if (!DIRECTIVES.contains(name)) {
                errors.add(lineOf(source, matcher.start()) + " 使用了未登记的指令 " + name);
            }
        }

        Set<String> locals = collectLocals(source);
        List<Expression> expressions = new ArrayList<>();
        for (Matcher matcher = INTERPOLATION.matcher(source); matcher.find(); ) {
            expressions.add(new Expression(matcher.group(1), matcher.start()));
        }
        for (Matcher matcher = BINDING.matcher(source); matcher.find(); ) {
            expressions.add(new Expression(matcher.group(2), matcher.start()));
        }
        for (Expression expression : expressions) {
            for (String name : freeIdentifiers(expression.text())) {
                if (!JS_KEYWORDS.contains(name) && !locals.contains(name) && !TEMPLATE_KEYS.contains(name)) {
                    errors.add(lineOf(source, expression.index()) + " 引用了未登记的标识符 \"" + name + "\"");
                }
            }
        }

        validateComponentUsage(source, errors);

        if (!errors.isEmpty()) {
            throw ApiException.badRequest("主题 HTML 校验失败：\n- " + String.join("\n- ", errors));
        }
    }

    public static void validateCss(String css) {
        String lower = css.toLowerCase(Locale.ROOT);
        if (lower.contains("</style")) {
            throw ApiException.badRequest("主题 CSS 含 </style>，会逃逸样式块");
        }
        if (lower.contains("@import")) {
            throw ApiException.badRequest("主题 CSS 禁止使用 @import");
        }
        Matcher matcher = CSS_URL.matcher(css);
        while (matcher.find()) {
            String target = matcher.group(1).trim().replace("\"", "").replace("'", "");
            if (!target.startsWith("/api/media/") && !target.startsWith("data:")) {
                throw ApiException.badRequest("主题 CSS 仅允许 /api/media/ 与 data: 资源");
            }
        }

        // .bc-* 是组件的公开样式接口：主题只能覆盖已登记的 class，不能发明新的。
        String cleaned = CSS_COMMENT.matcher(css).replaceAll(" ");
        cleaned = CSS_STRING.matcher(cleaned).replaceAll(" ");
        Set<String> unknown = new LinkedHashSet<>();
        Matcher classes = CSS_CLASS.matcher(cleaned);
        while (classes.find()) {
            String name = classes.group(1);
            if (!ThemeContract.PUBLIC_PARTS.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("主题 CSS 使用了未登记的 .bc-* class：" + String.join(", ", unknown));
        }
    }

    /**
     * 组件使用约束：必填 props、组件事件、slot 名与 slot 作用域字段。
     *
     * <p>slot 采用 union 口径（与前端 check:contract 一致）：只校验“这个名字存在”，
     * 不判断它是否属于当前组件；精确到组件需要完整模板解析，留待后续。
     */
    private static void validateComponentUsage(String source, List<String> errors) {
        for (Matcher matcher = COMPONENT_WITH_ATTRS.matcher(source); matcher.find(); ) {
            String tag = matcher.group(1);
            String attrs = matcher.group(2) == null ? "" : matcher.group(2);
            ThemeContract.ComponentDef def = ThemeContract.COMPONENT_DEFS.get(tag);
            if (def == null) {
                continue;
            }
            for (String prop : def.requiredProps()) {
                Pattern required = Pattern.compile(
                        "(?<![\\w-])(?:(?::|v-bind:)" + Pattern.quote(prop) + "(?=[\\s=/>])|"
                                + Pattern.quote(prop) + "\\s*=)");
                if (!required.matcher(attrs).find()) {
                    errors.add(lineOf(source, matcher.start()) + " <" + tag
                            + "> 缺少必填 prop \"" + prop + "\"");
                }
            }
            if (!def.events().isEmpty()) {
                Matcher events = EVENT_ATTR.matcher(attrs);
                while (events.find()) {
                    String event = events.group(1);
                    if (!def.events().contains(event)) {
                        errors.add(lineOf(source, matcher.start()) + " <" + tag
                                + "> 使用了未登记事件 \"" + event + "\"");
                    }
                }
            }
        }

        Matcher slots = SLOT_USAGE.matcher(source);
        while (slots.find()) {
            String name = slots.group(1) != null
                    ? slots.group(1)
                    : (slots.group(2) != null ? slots.group(2) : "default");
            if (!ThemeContract.SLOT_NAMES.contains(name)) {
                errors.add(lineOf(source, slots.start()) + " 使用了未登记的 slot #" + name);
                continue;
            }
            String value = slots.group(3) == null ? "" : slots.group(3).trim();
            int end = value.lastIndexOf('}');
            String inner = value.startsWith("{") && end > 0 ? value.substring(1, end) : value;
            Set<String> fields = ThemeContract.SLOT_SCOPE.getOrDefault(name, Set.of());
            for (String part : inner.split(",")) {
                String field = part.split(":")[0].trim().replaceFirst("^\\.\\.\\.", "");
                if (!field.isEmpty() && field.matches("[A-Za-z_$][\\w$]*") && !fields.contains(field)) {
                    errors.add(lineOf(source, slots.start()) + " slot #" + name
                            + " 引用了未登记的作用域字段 \"" + field + "\"");
                }
            }
        }
    }

    private static Set<String> collectLocals(String html) {
        Set<String> locals = new LinkedHashSet<>();
        for (Matcher matcher = V_FOR.matcher(html); matcher.find(); ) {
            for (String part : matcher.group(1).split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    locals.add(name);
                }
            }
        }
        for (Matcher matcher = SLOT_SCOPE.matcher(html); matcher.find(); ) {
            String value = matcher.group(1).trim();
            String inner = value.startsWith("{") ? value.substring(1, value.lastIndexOf('}')) : value;
            for (String part : inner.split(",")) {
                String[] pieces = part.split(":");
                String name = (pieces.length > 1 ? pieces[1] : pieces[0]).trim();
                if (name.matches("[A-Za-z_$][\\w$]*")) {
                    locals.add(name);
                }
            }
        }
        return locals;
    }

    private static List<String> freeIdentifiers(String expression) {
        Set<String> arrowParams = new LinkedHashSet<>();
        for (Matcher matcher = ARROW_PARAMS.matcher(expression); matcher.find(); ) {
            String params = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            for (String part : params.split(",")) {
                String name = part.split("=")[0].trim().replaceFirst("^\\.\\.\\.", "");
                if (name.contains(":")) {
                    name = name.substring(name.lastIndexOf(':') + 1).trim();
                }
                name = name.replaceAll("[{}\\[\\]]", "").trim();
                if (name.matches("[A-Za-z_$][\\w$]*")) {
                    arrowParams.add(name);
                }
            }
        }

        String cleaned = stripArrowParams(expression);
        cleaned = STRING_LITERAL.matcher(cleaned).replaceAll(" ");

        List<String> names = new ArrayList<>();
        Matcher matcher = FREE_IDENTIFIER.matcher(cleaned);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (arrowParams.contains(name)) {
                continue;
            }
            boolean objectKey = afterIsColon(cleaned, matcher.end())
                    && isObjectKeyBoundary(cleaned, matcher.start());
            if (objectKey) {
                continue;
            }
            names.add(name);
        }
        return names;
    }

    private static String stripArrowParams(String expression) {
        Matcher matcher = ARROW_PARAMS.matcher(expression);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            builder.append(expression, last, matcher.start());
            builder.append(" ".repeat(matcher.end() - matcher.start()));
            last = matcher.end();
        }
        builder.append(expression.substring(last));
        return builder.toString();
    }

    private static boolean afterIsColon(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor < text.length() && text.charAt(cursor) == ':';
    }

    private static boolean isObjectKeyBoundary(String text, int index) {
        int cursor = index - 1;
        while (cursor >= 0 && Character.isWhitespace(text.charAt(cursor))) {
            cursor--;
        }
        return cursor < 0 || text.charAt(cursor) == '{' || text.charAt(cursor) == ',';
    }

    private static String lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < Math.min(index, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return "第 " + line + " 行";
    }

    private record Expression(String text, int index) {
    }
}
