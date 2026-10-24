package cc.naihao.simplenotes.site;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 站点配置注册表（docs/blog-runtime-theme.md §3.5 / §6.2）：键、类型、默认值、校验与公开范围的唯一来源。
 *
 * <p>新增配置 = 本表加一条定义（纯增量，不升契约）；改名 / 删除属于 L0 破坏性变更。
 */
public final class SiteSettingCatalog {

    /** 内置导航项标识 → 路径（主题拿到的 {@code to} 由服务端派生，避免配置里写死路径）。 */
    private static final Map<String, String> NAV_PATHS = Map.of(
            "home", "/",
            "archives", "/archives",
            "tags", "/tags",
            "search", "/search",
            "about", "/about");

    private static final String DEFAULT_NAV = """
            {"v":1,"items":[
              {"key":"home","label":"首页","visible":true,"order":10},
              {"key":"archives","label":"归档","visible":true,"order":20},
              {"key":"tags","label":"标签","visible":true,"order":30},
              {"key":"search","label":"搜索","visible":true,"order":40},
              {"key":"about","label":"关于","visible":true,"order":50}
            ]}""";

    private static final String DEFAULT_SOCIAL = """
            {"v":1,"items":[]}""";

    private static final Map<String, SettingDefinition> DEFINITIONS = build();

    private SiteSettingCatalog() {
    }

    public static Map<String, SettingDefinition> definitions() {
        return DEFINITIONS;
    }

    public static Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(DEFINITIONS.get(key));
    }

    /** 内置导航 key 对应的路径；未知 key 返回 null（由调用方过滤） */
    public static String navPath(String key) {
        return NAV_PATHS.get(key);
    }

    private static Map<String, SettingDefinition> build() {
        Map<String, SettingDefinition> map = new LinkedHashMap<>();

        map.put("site.name", SettingDefinition.string(
                "site.name", "品牌", "站点名称", "Simple Notes", true));
        map.put("site.tagline", SettingDefinition.string(
                "site.tagline", "品牌", "副标题", "随手记，也认真写博客", true));
        map.put("site.description", SettingDefinition.string(
                "site.description", "品牌", "站点描述", "", true));
        map.put("site.baseUrl", SettingDefinition.string(
                "site.baseUrl", "品牌", "公开地址", "", true));
        map.put("site.logoUrl", SettingDefinition.string(
                "site.logoUrl", "品牌", "Logo 地址", "", true));
        map.put("site.faviconUrl", SettingDefinition.string(
                "site.faviconUrl", "品牌", "Favicon 地址", "", true));
        map.put("site.icp", SettingDefinition.string(
                "site.icp", "品牌", "ICP 备案号", "", true));
        map.put("site.policeRecord", SettingDefinition.string(
                "site.policeRecord", "品牌", "公安备案号", "", true));

        map.put("author.name", SettingDefinition.string(
                "author.name", "作者", "展示名", "", true));
        map.put("author.avatarUrl", SettingDefinition.string(
                "author.avatarUrl", "作者", "头像地址", "", true));
        map.put("author.bio", SettingDefinition.string(
                "author.bio", "作者", "一句话简介", "", true));

        map.put("social.links", SettingDefinition.json(
                "social.links", "作者", "社交链接", DEFAULT_SOCIAL, true));

        map.put("nav.items", SettingDefinition.json(
                "nav.items", "导航", "导航项", DEFAULT_NAV, true));

        map.put("home.pageSize", SettingDefinition.number(
                "home.pageSize", "首页", "每页文章数", 10, true, 1, 50));

        map.put("footer.text", SettingDefinition.string(
                "footer.text", "页脚", "页脚文案", "", true));

        map.put("theme.id", SettingDefinition.string(
                "theme.id", "主题", "当前主题", "", true));
        map.put("theme.mode", SettingDefinition.string(
                "theme.mode", "主题", "默认明暗", "system", true,
                List.of("system", "light", "dark")));
        map.put("theme.overrides", SettingDefinition.json(
                "theme.overrides", "主题", "Token 覆盖", "{}", true));

        return java.util.Collections.unmodifiableMap(map);
    }
}
