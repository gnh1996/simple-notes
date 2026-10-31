package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 主题模板 / theme.overrides 保存期校验（docs/blog-runtime-theme.md §3.6 / §8）。 */
class ThemeTemplateValidatorTest {

    @Test
    @DisplayName("内置默认主题通过契约扫描")
    void defaultThemePasses() throws Exception {
        Path theme = Path.of("..", "frontend", "packages", "theme-starter", "theme.html");
        Assumptions.assumeTrue(Files.exists(theme), "前端默认主题不在当前工作区");
        String html = Files.readString(theme);
        assertThatCode(() -> ThemeTemplateValidator.validateHtml(html)).doesNotThrowAnyException();

        Path fixture = Path.of("..", "frontend", "scripts", "fixtures", "theme-capabilities.html");
        Assumptions.assumeTrue(Files.exists(fixture), "前端能力 fixture 不在当前工作区");
        String fixtureHtml = Files.readString(fixture);
        assertThatCode(() -> ThemeTemplateValidator.validateHtml(fixtureHtml)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝未登记组件 / 指令 / 标识符")
    void rejectsUnknownCapabilities() {
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("<div id=\"app\"><blog-unknown /></div>"))
                .hasMessageContaining("未登记的组件");
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("<div id=\"app\" v-html=\"x\"></div>"))
                .hasMessageContaining("禁用内容");
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("<div id=\"app\" v-foo=\"x\"></div>"))
                .hasMessageContaining("未登记的指令");
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("<div id=\"app\">{{ unknownKey }}</div>"))
                .hasMessageContaining("未登记的标识符");
    }

    @Test
    @DisplayName("允许 v-for / slot 作用域 / 箭头函数局部变量")
    void allowsLocals() {
        String html = """
                <div id="app">
                  <blog-post-card v-for="(post, index) in posts" :key="post.slug" :post="post">
                    <template #default="{ post: item }">{{ item.title }}</template>
                  </blog-post-card>
                  <p>{{ posts.filter(item => item.title.includes(ui.keyword || '')).length }}</p>
                </div>
                """;
        assertThatCode(() -> ThemeTemplateValidator.validateHtml(html)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("theme.overrides 只接受契约变量与安全值")
    void validatesThemeOverrides() {
        assertThatCode(() -> SiteSettingCatalog.validateThemeOverrides(
                Map.of("--blog-accent", "#b23a2f", "--blog-prose-size", "18px")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SiteSettingCatalog.validateThemeOverrides(
                Map.of("--not-registered", "#fff")))
                .hasMessageContaining("未知变量");
        assertThatThrownBy(() -> SiteSettingCatalog.validateThemeOverrides(
                Map.of("--blog-bg", "red; background: url(https://evil.example/x)")))
                .hasMessageContaining("非法 CSS 值");
        assertThatThrownBy(() -> SiteSettingCatalog.validateThemeOverrides(
                Map.of("--blog-gap", 12)))
                .hasMessageContaining("必须是字符串");
    }
}
