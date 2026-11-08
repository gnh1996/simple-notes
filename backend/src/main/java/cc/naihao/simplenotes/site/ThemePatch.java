package cc.naihao.simplenotes.site;

import java.util.List;

/**
 * 主题编辑补丁（docs/theme-ai.md §2.3/§3，v2.1）。
 *
 * <p>首轮/从零生成走完整 html/css；已有当前主题时，模型只输出本补丁。
 * 补丁是“小 JSON”，不承载整篇主题，避免 JSON 转义、输出截断和上下文膨胀。
 */
public record ThemePatch(
        /** 1-3 句中文说明 */
        String summary,
        /** 3-5 行本轮要点 */
        List<String> plan,
        /** 编辑操作，按顺序应用 */
        List<ThemeOp> ops) {

    /**
     * 单个编辑操作。字段按 op 取用，未使用字段允许为空。
     *
     * <ul>
     *   <li>{@code setCssVar}：name + value</li>
     *   <li>{@code appendCss}：css</li>
     *   <li>{@code replaceCss}：css（完整 CSS）</li>
     *   <li>{@code replaceHtml}：html（完整 HTML）</li>
     *   <li>{@code replace}：target=html|css，find + content</li>
     * </ul>
     */
    public record ThemeOp(
            String op,
            String name,
            String value,
            String css,
            String html,
            String target,
            String find,
            String content) {
    }
}
