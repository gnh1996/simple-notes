package cc.naihao.simplenotes.site;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型的 {@link ThemePatch} 应用到当前主题（docs/theme-ai.md §3，v2.1）。
 *
 * <p>纯函数，不依赖 Spring；应用后仍由 {@link AiThemePrompt#validate} 做契约校验。
 * 操作失败不会改动入参，返回上一版主题 + 可读错误，交给修复轮。
 */
public final class AiThemePatchApplier {

    static final int MAX_OPS = 20;

    private AiThemePatchApplier() {
    }

    public static Applied apply(String html, String css, ThemePatch patch) {
        String currentHtml = html == null ? "" : html;
        String currentCss = css == null ? "" : css;
        List<String> errors = new ArrayList<>();
        if (patch == null || patch.ops() == null || patch.ops().isEmpty()) {
            return new Applied(currentHtml, currentCss, 0, List.of("补丁没有 ops，请输出至少一个编辑操作"));
        }
        if (patch.ops().size() > MAX_OPS) {
            return new Applied(currentHtml, currentCss, 0,
                    List.of("补丁操作过多（" + patch.ops().size() + "），单轮最多 " + MAX_OPS + " 个"));
        }

        int applied = 0;
        for (int i = 0; i < patch.ops().size(); i++) {
            ThemePatch.ThemeOp op = patch.ops().get(i);
            String label = "第 " + (i + 1) + " 个操作";
            try {
                switch (op == null || op.op() == null ? "" : op.op()) {
                    case "setCssVar" -> currentCss = setCssVar(currentCss, op);
                    case "appendCss" -> currentCss = appendCss(currentCss, op);
                    case "replaceCss" -> currentCss = replaceCss(op);
                    case "replaceHtml" -> currentHtml = replaceHtml(op);
                    case "replace" -> {
                        if ("css".equalsIgnoreCase(op.target())) {
                            currentCss = replaceOnce(currentCss, op, "CSS");
                        } else if ("html".equalsIgnoreCase(op.target())) {
                            currentHtml = replaceOnce(currentHtml, op, "HTML");
                        } else {
                            throw new IllegalArgumentException("replace.target 必须是 html 或 css");
                        }
                    }
                    default -> throw new IllegalArgumentException("未知操作：" + op.op());
                }
            } catch (RuntimeException e) {
                errors.add(label + "（" + (op == null ? "?" : op.op()) + "）失败：" + e.getMessage());
                break;
            }
            applied++;
        }
        if (!errors.isEmpty()) {
            return new Applied(html == null ? "" : html, css == null ? "" : css, applied, errors);
        }
        if (bytes(currentHtml) > AiThemePrompt.HTML_MAX) {
            errors.add("补丁应用后 HTML 超出 64KB 上限");
        }
        if (bytes(currentCss) > AiThemePrompt.CSS_MAX) {
            errors.add("补丁应用后 CSS 超出 256KB 上限");
        }
        return new Applied(currentHtml, currentCss, applied, errors);
    }

    private static String setCssVar(String css, ThemePatch.ThemeOp op) {
        String name = require(op.name(), "setCssVar.name");
        if (!ThemeContract.CSS_VARS.contains(name)) {
            throw new IllegalArgumentException("CSS 变量不在白名单：" + name);
        }
        String value = require(op.value(), "setCssVar.value").strip();
        if (value.isEmpty() || value.length() > 300) {
            throw new IllegalArgumentException("CSS 变量值长度不合法");
        }
        if (value.contains(";") || value.contains("{") || value.contains("}") || value.contains("<")
                || value.contains(">") || value.contains("\n") || value.contains("\r")
                || value.contains("@import")) {
            throw new IllegalArgumentException("CSS 变量值含不允许的字符");
        }
        if (value.contains("url(") && !value.contains("/api/media/") && !value.contains("data:")) {
            throw new IllegalArgumentException("CSS 变量值中的 url() 只允许 /api/media/ 与 data:");
        }
        // 替换首个同名变量定义（通常是 :root），保留主题原有的深色覆盖；没有定义时追加 :root。
        Pattern definition = Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + "\\s*:\\s*[^;]+;");
        Matcher matcher = definition.matcher(css == null ? "" : css);
        String declaration = name + ": " + value + ";";
        if (matcher.find()) {
            return css.substring(0, matcher.start()) + declaration + css.substring(matcher.end());
        }
        return append(css, ":root { " + declaration + " }");
    }

    private static String appendCss(String css, ThemePatch.ThemeOp op) {
        String addition = require(op.css(), "appendCss.css").strip();
        if (addition.isEmpty()) {
            throw new IllegalArgumentException("appendCss.css 为空");
        }
        return append(css, addition);
    }

    private static String replaceCss(ThemePatch.ThemeOp op) {
        String replacement = require(op.css(), "replaceCss.css").strip();
        if (replacement.isEmpty()) {
            throw new IllegalArgumentException("replaceCss.css 为空");
        }
        return replacement;
    }

    private static String replaceHtml(ThemePatch.ThemeOp op) {
        String replacement = require(op.html(), "replaceHtml.html").strip();
        if (replacement.isEmpty()) {
            throw new IllegalArgumentException("replaceHtml.html 为空");
        }
        return replacement;
    }

    private static String replaceOnce(String source, ThemePatch.ThemeOp op, String label) {
        String find = require(op.find(), "replace.find");
        String content = op.content() == null ? "" : op.content();
        int first = source.indexOf(find);
        if (first < 0) {
            throw new IllegalArgumentException(label + " 中找不到 find 片段（请从当前主题原样复制一段唯一内容）");
        }
        if (source.indexOf(find, first + find.length()) >= 0) {
            throw new IllegalArgumentException(label + " 中 find 片段不唯一，请扩大片段范围");
        }
        return source.substring(0, first) + content + source.substring(first + find.length());
    }

    private static String append(String css, String addition) {
        if (css == null || css.isBlank()) {
            return addition;
        }
        return css + "\n" + addition;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public record Applied(String html, String css, int applied, List<String> errors) {
    }
}
