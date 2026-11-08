package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 主题提示词装配与产物解析（docs/theme-ai.md §2.3/§3，v2.1）。 */
class AiThemePromptTest {

    @Test
    @DisplayName("完整模式要求 <plan> + html/css fenced；补丁模式要求 JSON ops")
    void promptsMatchMode() {
        String full = AiThemePrompt.fullSystemPrompt(null, null);
        assertThat(full).contains("<plan>").contains("```html").contains("```css");
        assertThat(full).doesNotContain("\"ops\"");

        String patch = AiThemePrompt.patchSystemPrompt("<div id=\"app\"></div>", ".a{}");
        assertThat(patch).contains("JSON").contains("ops").contains("setCssVar");
        assertThat(patch).contains("<reference-html>");
    }

    @Test
    @DisplayName("完整模式：抽取 html/css、plan 与说明")
    void extractsFullTheme() {
        String text = """
                <plan>
                - 改成暗色
                - 卡片加大圆角
                </plan>
                这次用暗色 + 大圆角。
                ```html
                <div id="app"><p>{{ site.name }}</p></div>
                ```
                ```css
                :root { --blog-radius: 16px; }
                ```
                """;
        AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.html()).contains("id=\"app\"");
        assertThat(extracted.css()).contains("--blog-radius");
        assertThat(extracted.plan()).containsExactly("改成暗色", "卡片加大圆角");
        assertThat(extracted.summary()).contains("暗色 + 大圆角");
    }

    @Test
    @DisplayName("完整模式：缺块报错")
    void fullThemeMissingBlocks() {
        AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull("只有说明");
        assertThat(extracted.errors()).hasSize(2);
    }

    @Test
    @DisplayName("完整模式兼容旧 JSON 产物（html/css 字段）")
    void fullThemeLegacyJson() {
        AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull("""
                说明
                {"summary":"旧格式","plan":["一步"],"html":"<div id=\\"app\\"></div>","css":".a{}"}
                """);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.html()).contains("id=\"app\"");
        assertThat(extracted.summary()).isEqualTo("旧格式");
        assertThat(extracted.plan()).containsExactly("一步");
    }

    @Test
    @DisplayName("补丁模式：summary 开头的裸 JSON + 前后散文也能抽取")
    void extractsPatchWithSummaryFirst() {
        String text = """
                好的，我来调整：
                {"summary":"卡片圆角加大","plan":["调整变量"],
                 "ops":[{"op":"setCssVar","name":"--blog-radius","value":"16px"}]}
                希望你喜欢。
                """;
        AiThemePrompt.PatchExtracted extracted = AiThemePrompt.extractPatch(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.patch().summary()).isEqualTo("卡片圆角加大");
        assertThat(extracted.patch().plan()).containsExactly("调整变量");
        assertThat(extracted.patch().ops()).hasSize(1);
        assertThat(extracted.patch().ops().getFirst().op()).isEqualTo("setCssVar");
        assertThat(extracted.json()).contains("\"ops\"");
    }

    @Test
    @DisplayName("补丁模式：fenced JSON、未知字段、字符串 plan 都能容错")
    void extractsPatchFencedAndLenient() {
        String text = """
                ```json
                {"summary":"换字体","plan":"调整字体","extra":true,
                 "ops":[{"op":"appendCss","css":".x{}","unused":"x"}]}
                ```
                """;
        AiThemePrompt.PatchExtracted extracted = AiThemePrompt.extractPatch(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.patch().summary()).isEqualTo("换字体");
        assertThat(extracted.patch().plan()).containsExactly("调整字体");
        assertThat(extracted.patch().ops().getFirst().css()).isEqualTo(".x{}");
    }

    @Test
    @DisplayName("补丁模式：非法 JSON / 缺 ops 报可读错误")
    void patchErrors() {
        assertThat(AiThemePrompt.extractPatch("没有 JSON").errors()).hasSize(1);
        assertThat(AiThemePrompt.extractPatch("{\"summary\":\"x\"}").errors()).hasSize(1);
        assertThat(AiThemePrompt.extractPatch("```json\n{not json}\n```").errors()).hasSize(1);
    }

    @Test
    @DisplayName("补丁模式：replaceHtml 内含模板花括号/引号也能按字符串配平")
    void extractsPatchWithHtmlString() {
        String text = """
                {"summary":"重写结构","plan":[],
                 "ops":[{"op":"replaceHtml","html":"<div id=\\"app\\">{{ site.name }}</div>"}]}
                """;
        AiThemePrompt.PatchExtracted extracted = AiThemePrompt.extractPatch(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.patch().ops().getFirst().html()).contains("id=\"app\"").contains("{{ site.name }}");
    }

    @Test
    @DisplayName("补丁模式：截断 JSON 报解析失败而不是抛异常")
    void truncatedPatchIsError() {
        AiThemePrompt.PatchExtracted extracted = AiThemePrompt.extractPatch(
                "{\"summary\":\"x\",\"ops\":[{\"op\":\"appendCss\",\"css\":\".a{color:red}\"");
        assertThat(extracted.errors()).hasSize(1);
    }

    @Test
    @DisplayName("非法标识符被收集为错误（含行号信息），不抛异常")
    void collectsValidationErrors() {
        AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull("""
                ```html
                <div id="app">{{ unknownKey }}</div>
                ```
                ```css
                .a { color: red; }
                ```
                """);
        List<String> errors = AiThemePrompt.validate(extracted.html(), extracted.css(), extracted.errors());
        assertThat(errors).anyMatch(message -> message.contains("unknownKey"));
    }

    @Test
    @DisplayName("内置默认主题抽取后能通过校验")
    void defaultThemePasses() throws Exception {
        Path htmlPath = Path.of("..", "frontend", "packages", "theme-starter", "theme.html");
        Path cssPath = Path.of("..", "frontend", "packages", "theme-starter", "theme.css");
        Assumptions.assumeTrue(Files.exists(htmlPath), "前端默认主题不在当前工作区");
        String text = "```html\n" + Files.readString(htmlPath) + "\n```\n```css\n"
                + Files.readString(cssPath) + "\n```";
        AiThemePrompt.Extracted extracted = AiThemePrompt.extractFull(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(AiThemePrompt.validate(extracted.html(), extracted.css(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("记忆压缩：整篇主题只留 summary/plan，不保留 html/css")
    void compactsMemory() {
        String compact = AiThemePrompt.compactForMemory("""
                <plan>- 改暗色</plan>
                这是说明。
                ```html
                <div id="app"><div style="height:10000px">x</div></div>
                ```
                ```css
                .a { color: red; }
                ```
                """, "assistant");
        assertThat(compact).contains("这是说明").contains("改暗色");
        assertThat(compact).doesNotContain("10000px").doesNotContain(".a { color");
    }
}
