package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 主题提示词与产物解析（docs/theme-ai.md §3，纯函数，可单测）。
 *
 * <p>契约清单与 {@code frontend/apps/blog/src/contract.ts}、
 * {@link ThemeTemplateValidator} 保持同名；改契约时同步改 {@link #SYSTEM_RULES}。
 */
public final class AiThemePrompt {

    static final int HTML_MAX = 64 * 1024;
    static final int CSS_MAX = 256 * 1024;

    /**
     * 系统规则（契约白名单摘要）。L0 冻结项只增不改，新增组件 / 数据键时同步追加。
     */
    static final String SYSTEM_RULES = """
            你是博客主题设计师，只输出主题，不解释与主题无关的内容。
            主题 = 整页 HTML + 全部 CSS，零 JavaScript。
            用户的审美指令优先级最高：如果用户要求全新风格（卡通 / 杂志 / 极简 / 暗黑等），
            允许重写整体 DOM 结构、区块顺序与全部样式，只要满足下面的硬约束。
            不要为了省事而照抄起点模板——起点只是契约用法的参考，不是设计终点。

            【硬约束（违反会被校验器拒绝）】
            1. HTML 必须包含 id="app" 根节点；禁止 <script>、v-html、javascript:、window.、document.、localStorage、fetch(、eval(、import(。
            2. 只允许以下组件标签：blog-post-card, blog-pager, blog-markdown, blog-toc, blog-tag-chip, blog-tag-cloud, \
            blog-archives, blog-search-box, blog-theme-toggle, blog-image, blog-back-to-top, blog-reading-progress, blog-state。
            3. 只允许以下指令：v-if, v-else-if, v-else, v-for, v-show, v-model（仅 v-model="ui.xxx"）, v-text, v-once, v-cloak, v-bind, v-on, v-slot。\
            不允许除 @click 调用动作外的其他脚本表达式。
            4. 模板可引用的数据键只有：site, nav, route, state, message, colorMode, posts, pager, post, prevPost, nextPost, \
            tags, tag, archives, archiveGroups, keyword, searched, page, listSort, ui；辅助函数只有 isNavActive, formatDate, postDate；\
            动作只有 go, search, setColorMode, toggleColorMode, scrollToTop, openLightbox。引用其他标识符会被拒绝（v-for 局部变量与 slot 作用域除外）。
            5. 页面分支（route.name）：home 首页, post 文章详情, tags 标签, tag-posts 标签文章, archives 归档, search 搜索, page 独立页面，其他走 404 分支。\
            缺失的页面分支运行时回落默认主题，但首屏 home 与 post 必须完整实现。
            6. Markdown 必须用 <blog-markdown :source="..."> 渲染，不得自行解析；列表用 v-for + blog-post-card；分页用 blog-pager。
            7. CSS 禁止 @import；url() 只允许 /api/media/ 与 data:；体积 HTML ≤ 64KB、CSS ≤ 256KB。
            8. 覆盖样式只允许这些 CSS 变量：--blog-accent, --blog-accent-contrast, --blog-bg, --blog-surface, --blog-text, \
            --blog-text-muted, --blog-border, --blog-radius, --blog-font-sans, --blog-mono, --blog-prose-size, \
            --blog-prose-line-height, --blog-content-width, --blog-gap, --blog-code-bg。
            9. 移动端 320/375/414 无横向滚动；可点元素 ≥ 44px；明暗两套配色（[data-color-mode] 或 colorMode）；中文正文 16-17px、行高 ≥ 1.7。

            【输出格式（严格遵守）】
            - 先输出 <plan> 块：3-5 行本轮要做的事（每行以 - 开头，只写要点不展开），再用 1-3 句中文说明设计要点，
            然后输出两个 fenced 代码块：```html ... ``` 与 ```css ... ``` 各恰好一个。
            - 不要输出 JSON，不要把代码块嵌在其他块里；修改时总是输出完整文件（不是 diff）。
            """;

    private static final Pattern HTML_BLOCK =
            Pattern.compile("```\\s*html\\s*\\n(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_BLOCK =
            Pattern.compile("```\\s*css\\s*\\n(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private AiThemePrompt() {
    }

    /** 首轮 system prompt：规则 + starter 全文（ starter 只在首轮带，后续轮用快照）。 */
    public static String systemPrompt(String starterHtml, String starterCss) {
        StringBuilder prompt = new StringBuilder(SYSTEM_RULES);
        if (starterHtml != null && !starterHtml.isBlank()) {
            prompt.append("\n【内置默认主题（仅作契约用法参考：组件 / 数据键 / 指令的正确写法；设计上不要模仿它，用户要什么风格就做什么风格）】\n```html\n")
                    .append(starterHtml)
                    .append("\n```\n");
        }
        if (starterCss != null && !starterCss.isBlank()) {
            prompt.append("```css\n").append(starterCss).append("\n```\n");
        }
        return prompt.toString();
    }

    /** 修复轮 prompt：把校验错误喂回去，要求输出完整修正版。 */
    public static String repairPrompt(List<String> errors) {
        return "上一版未通过主题校验（如下）。请输出完整修正后的 ```html / ```css（不是 diff），"
                + "先输出 <plan> 块（针对下面每条错误各写一行怎么改），再用 1-2 句说明改了哪里：\n- "
                + String.join("\n- ", errors);
    }

    /** 从模型输出中抽取 html/css 代码块；抽不到则 errors 非空。 */
    public static Extracted extract(String text) {
        List<String> errors = new ArrayList<>();
        String html = firstGroup(HTML_BLOCK, text);
        String css = firstGroup(CSS_BLOCK, text);
        if (html == null) {
            errors.add("缺少 ```html 代码块");
            html = "";
        }
        if (css == null) {
            errors.add("缺少 ```css 代码块");
            css = "";
        }
        return new Extracted(html.strip(), css.strip(), errors);
    }

    /** 体积 + ThemeTemplateValidator 双校验，返回全部错误（为空即通过）。 */
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
        return errors;
    }

    private static String firstGroup(Pattern pattern, String text) {
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

    public record Extracted(String html, String css, List<String> errors) {
    }
}
