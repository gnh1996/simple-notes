package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("实体编码的操作符不误判：outerHTML 把 && / < / > 转写为实体，校验前已归一")
    void serializedEntitiesPass() {
        // 运行加载时校验吃的是 app.outerHTML（浏览器序列化产物），与这里的输入同形态
        String outerHtmlLike = """
                <div id="app">
                  <blog-state v-if="state !== 'ready' || (searched &amp;&amp; !posts.length)" :state="state"></blog-state>
                  <p>{{ posts.filter(item =&gt; item.title).length }} 篇</p>
                  <p :class="{ active: listSort.direction == 'desc' &amp;&amp; state == 'ready' }">归档</p>
                  <p :title="pager.total &gt; 0 ? keyword : 'y'">比较</p>
                  <p>{{ pager.total &lt; 5 ? '少' : '多' }}</p>
                </div>
                """;
        assertThatCode(() -> ThemeTemplateValidator.validateHtml(outerHtmlLike)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("编码后的禁用内容同样拒绝：&lt;script 与字面 <script 同口径")
    void encodedScriptRejected() {
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml(
                "<div id=\"app\">&lt;script&gt;alert(1)&lt;/script&gt;</div>"))
                .hasMessageContaining("禁用内容");
    }

    @Test
    @DisplayName("headless fixture 通过校验：root slot 接管结构")
    void headlessFixturePasses() throws Exception {
        Path fixture = Path.of("..", "frontend", "scripts", "fixtures", "theme-headless.html");
        Assumptions.assumeTrue(Files.exists(fixture), "headless fixture 不在当前工作区");
        String html = Files.readString(fixture);
        assertThatCode(() -> ThemeTemplateValidator.validateHtml(html)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝未登记 slot / 作用域字段 / 缺必填 props / 未登记事件")
    void rejectsSlotAndPropErrors() {
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("""
                <div id="app">
                  <blog-post-card v-for="post in posts" :key="post.slug" :post="post"
                                  v-slot:missing="{ post }"></blog-post-card>
                </div>
                """)).hasMessageContaining("未登记的 slot");

        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("""
                <div id="app">
                  <blog-post-card v-for="post in posts" :key="post.slug" :post="post"
                                  v-slot:root="{ unknown }"></blog-post-card>
                </div>
                """)).hasMessageContaining("未登记的作用域字段");

        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml(
                "<div id=\"app\"><blog-post-card></blog-post-card></div>"))
                .hasMessageContaining("缺少必填 prop");

        // v-for 的局部变量不能被误判成 prop 绑定
        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml("""
                <div id="app">
                  <blog-post-card v-for="post in posts" :key="post.slug"></blog-post-card>
                </div>
                """)).hasMessageContaining("缺少必填 prop");

        assertThatThrownBy(() -> ThemeTemplateValidator.validateHtml(
                "<div id=\"app\"><blog-pager :pager=\"pager\" @change=\"go\"></blog-pager></div>"))
                .hasMessageContaining("未登记事件");
    }

    @Test
    @DisplayName("CSS 拒绝 </style> 逃逸与未登记的 .bc-* class")
    void rejectsCssEscapeAndUnknownParts() {
        assertThatThrownBy(() -> ThemeTemplateValidator.validateCss(".a{color:red}</style><script>"))
                .hasMessageContaining("</style>");
        assertThatThrownBy(() -> ThemeTemplateValidator.validateCss(".bc-unknown{color:red}"))
                .hasMessageContaining("未登记的 .bc-* class");
        assertThatCode(() -> ThemeTemplateValidator.validateCss(".bc-card{color:red}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("契约生成物包含类型 / slot scope / 路由，且修正了 --blog-mono 漂移")
    void contractProjectionLoaded() {
        assertThat(ThemeContract.COMPONENT_DEFS.get("blog-pager").slots())
                .anyMatch(slot -> slot.name().equals("root") && slot.provides().contains("pages"));
        assertThat(ThemeContract.ROUTES).containsKeys("home", "post", "archives");
        assertThat(ThemeContract.root().get("types").get("PostSummary").get("slug").asString())
                .isEqualTo("string");
        assertThat(ThemeContract.CSS_VARS).contains("--blog-font-mono").doesNotContain("--blog-mono");
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
