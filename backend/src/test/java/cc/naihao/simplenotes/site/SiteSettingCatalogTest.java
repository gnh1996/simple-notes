package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 站点配置注册表：键唯一、默认值完整、公开范围与校验元信息（docs/blog-runtime-theme.md §3.5）。 */
class SiteSettingCatalogTest {

    @Test
    @DisplayName("注册表包含约定的核心键，且默认值不为 null")
    void definitionsAreComplete() {
        var definitions = SiteSettingCatalog.definitions();

        assertThat(definitions).containsKeys(
                "site.name", "site.tagline", "site.baseUrl", "author.name",
                "social.links", "nav.items", "home.pageSize", "footer.text",
                "theme.id", "theme.mode", "theme.overrides");

        definitions.values().forEach(definition -> {
            assertThat(definition.key()).isNotBlank();
            assertThat(definition.defaultValue()).isNotNull();
            assertThat(definition.group()).isNotBlank();
        });
    }

    @Test
    @DisplayName("关键默认值：站点名、每页 10 条、明暗跟随系统")
    void defaultsMatchDocumentedBehaviour() {
        var definitions = SiteSettingCatalog.definitions();

        assertThat(definitions.get("site.name").defaultValue()).isEqualTo("Simple Notes");
        assertThat(definitions.get("home.pageSize").defaultValue()).isEqualTo("10");
        assertThat(definitions.get("theme.mode").defaultValue()).isEqualTo("system");
        assertThat(definitions.get("theme.mode").allowedValues())
                .containsExactly("system", "light", "dark");
    }

    @Test
    @DisplayName("导航 key → 路径映射固定；未知 key 返回 null")
    void navPaths() {
        assertThat(SiteSettingCatalog.navPath("home")).isEqualTo("/");
        assertThat(SiteSettingCatalog.navPath("archives")).isEqualTo("/archives");
        assertThat(SiteSettingCatalog.navPath("about")).isEqualTo("/about");
        assertThat(SiteSettingCatalog.navPath("unknown")).isNull();
    }

    @Test
    @DisplayName("复杂值以 JSON 形态存储，且带形态版本")
    void jsonDefaultsCarryShapeVersion() {
        assertThat(SiteSettingCatalog.definitions().get("nav.items").defaultValue())
                .contains("\"v\":1");
        assertThat(SiteSettingCatalog.definitions().get("social.links").defaultValue())
                .contains("\"v\":1");
    }

    @Test
    @DisplayName("系统托管键：theme.id 只由主题管理写入，其余配置可编辑")
    void managedKeys() {
        assertThat(SiteSettingCatalog.isManaged("theme.id")).isTrue();
        assertThat(SiteSettingCatalog.isManaged("theme.mode")).isFalse();
        assertThat(SiteSettingCatalog.isManaged("site.name")).isFalse();
    }

    @Test
    @DisplayName("配置项按声明顺序排列（管理端分组展示依赖顺序）")
    void definitionsKeepOrder() {
        List<String> keys = List.copyOf(SiteSettingCatalog.definitions().keySet());
        assertThat(keys.indexOf("site.name")).isLessThan(keys.indexOf("theme.mode"));
        assertThat(keys.indexOf("theme.mode")).isLessThan(keys.indexOf("theme.overrides"));
    }
}
