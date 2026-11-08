package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 主题补丁应用（docs/theme-ai.md §3，v2.1）。 */
class AiThemePatchApplierTest {

    private static final String HTML = "<div id=\"app\"><main class=\"home\">首页</main></div>";
    private static final String CSS = ":root { --blog-radius: 8px; }";

    @Test
    @DisplayName("setCssVar 追加 :root 覆盖，appendCss 追加样式")
    void appliesCssOps() {
        ThemePatch patch = new ThemePatch("圆角", List.of("调大圆角"), List.of(
                new ThemePatch.ThemeOp("setCssVar", "--blog-radius", "16px", null, null, null, null, null),
                new ThemePatch.ThemeOp("appendCss", null, null, ".home { padding: 24px; }", null, null, null, null)));
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(HTML, CSS, patch);
        assertThat(applied.errors()).isEmpty();
        assertThat(applied.applied()).isEqualTo(2);
        assertThat(applied.css()).contains("--blog-radius: 16px").contains(".home { padding: 24px; }");
        assertThat(applied.html()).isEqualTo(HTML);
    }

    @Test
    @DisplayName("replace 要求 find 唯一；找不到 / 不唯一都给出可读错误，且不改入参")
    void replaceRequiresUniqueSnippet() {
        ThemePatch ok = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("replace", null, null, null, null, "html", "首页", "文章")));
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(HTML, CSS, ok);
        assertThat(applied.errors()).isEmpty();
        assertThat(applied.html()).contains("文章").doesNotContain("首页");

        ThemePatch missing = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("replace", null, null, null, null, "html", "不存在", "x")));
        assertThat(AiThemePatchApplier.apply(HTML, CSS, missing).errors()).isNotEmpty();
        assertThat(AiThemePatchApplier.apply(HTML, CSS, missing).html()).isEqualTo(HTML);

        String duplicate = "<div id=\"app\"><p>x</p><p>x</p></div>";
        ThemePatch ambiguous = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("replace", null, null, null, null, "html", "x", "y")));
        assertThat(AiThemePatchApplier.apply(duplicate, CSS, ambiguous).errors())
                .anyMatch(message -> message.contains("不唯一"));
    }

    @Test
    @DisplayName("setCssVar 替换首个定义（:root），保留深色主题的同名覆盖")
    void setCssVarKeepsDarkOverride() {
        String css = ":root { --blog-bg: #fff; --blog-radius: 8px; }\n"
                + "[data-color-mode=\"dark\"] { --blog-bg: #000; }";
        ThemePatch patch = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("setCssVar", "--blog-bg", "#123456", null, null, null, null, null)));
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(HTML, css, patch);
        assertThat(applied.errors()).isEmpty();
        assertThat(applied.css()).contains("--blog-bg: #123456").contains("[data-color-mode=\"dark\"] { --blog-bg: #000");
    }

    @Test
    @DisplayName("replaceHtml / replaceCss 支持整体重写")
    void replacesWhole() {
        ThemePatch patch = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("replaceHtml", null, null, null, "<div id=\"app\">新</div>", null, null, null),
                new ThemePatch.ThemeOp("replaceCss", null, null, ":root{--blog-bg:#000}", null, null, null, null)));
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(HTML, CSS, patch);
        assertThat(applied.errors()).isEmpty();
        assertThat(applied.html()).isEqualTo("<div id=\"app\">新</div>");
        assertThat(applied.css()).isEqualTo(":root{--blog-bg:#000}");
    }

    @Test
    @DisplayName("非法操作 / 非白名单变量 / 空 ops 都被拒绝")
    void rejectsInvalidOps() {
        assertThat(AiThemePatchApplier.apply(HTML, CSS, new ThemePatch("", List.of(), List.of())).errors())
                .isNotEmpty();

        ThemePatch unknown = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("deleteEverything", null, null, null, null, null, null, null)));
        assertThat(AiThemePatchApplier.apply(HTML, CSS, unknown).errors())
                .anyMatch(message -> message.contains("未知操作"));

        ThemePatch badVar = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("setCssVar", "--not-allowed", "red", null, null, null, null, null)));
        assertThat(AiThemePatchApplier.apply(HTML, CSS, badVar).errors())
                .anyMatch(message -> message.contains("白名单"));

        ThemePatch inject = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("setCssVar", "--blog-bg", "red; } body{display:none", null, null, null, null, null)));
        assertThat(AiThemePatchApplier.apply(HTML, CSS, inject).errors()).isNotEmpty();
    }

    @Test
    @DisplayName("应用后的简单主题能通过契约校验")
    void appliedThemeValidates() {
        ThemePatch patch = new ThemePatch("", List.of(), List.of(
                new ThemePatch.ThemeOp("appendCss", null, null, ":root{--blog-radius:16px}", null, null, null, null)));
        AiThemePatchApplier.Applied applied = AiThemePatchApplier.apply(HTML, CSS, patch);
        assertThat(AiThemePrompt.validate(applied.html(), applied.css(), applied.errors())).isEmpty();
    }
}
