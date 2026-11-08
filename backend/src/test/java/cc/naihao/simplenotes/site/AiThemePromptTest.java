package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 主题提示词装配与产物解析（docs/theme-ai.md §3，纯函数）。 */
class AiThemePromptTest {

    @Test
    @DisplayName("提示词要求先输出 <plan> 再给代码块（抽屉思维链的数据来源）")
    void promptsRequirePlan() {
        assertThat(AiThemePrompt.systemPrompt(null, null)).contains("<plan>");
        assertThat(AiThemePrompt.repairPrompt(List.of("第 1 行：引用了未登记的标识符"))).contains("<plan>");
    }

    @Test
    @DisplayName("抽取 html/css 代码块；缺块时报错")
    void extractsFencedBlocks() {
        String text = """
                设计说明：极简杂志风。
                ```html
                <div id="app"><p>{{ site.name }}</p></div>
                ```
                ```css
                .shell { color: red; }
                ```
                """;
        AiThemePrompt.Extracted extracted = AiThemePrompt.extract(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(extracted.html()).contains("id=\"app\"");
        assertThat(extracted.css()).contains(".shell");

        AiThemePrompt.Extracted missing = AiThemePrompt.extract("只有说明，没有代码块");
        assertThat(missing.errors()).hasSize(2);
    }

    @Test
    @DisplayName("非法标识符被收集为错误（含行号信息），不抛异常")
    void collectsValidationErrors() {
        AiThemePrompt.Extracted extracted = AiThemePrompt.extract("""
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
        AiThemePrompt.Extracted extracted = AiThemePrompt.extract(text);
        assertThat(extracted.errors()).isEmpty();
        assertThat(AiThemePrompt.validate(extracted.html(), extracted.css(), List.of())).isEmpty();
    }
}
