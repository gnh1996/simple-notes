package cc.naihao.simplenotes.site;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 主题契约的运行时投影（docs/theme-contract.md 规范 / docs/theme-contract-v2.md 2A 设计）。
 *
 * <p>唯一来源是前端 {@code apps/blog/src/contract.ts} + {@code types.ts} + {@code runtime.ts}；
 * {@code pnpm sync:contract} 生成 {@code classpath:theme/theme-contract.json}，本类只读该文件。
 * 校验器与 AI 提示词都从这里取数据，禁止再手写组件 / 字段 / CSS 变量清单。
 *
 * <p>读取失败直接抛错：契约是服务端校验与 AI 生成的前提，缺文件属于部署错误。
 */
public final class ThemeContract {

    private static final String RESOURCE = "theme/theme-contract.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode ROOT = load();

    public static final int VERSION = ROOT.get("version").asInt(1);

    public static final Set<String> DATA_KEYS = keys(ROOT.get("data"));
    public static final Set<String> HELPER_KEYS = keys(ROOT.get("helpers"));
    public static final Set<String> ACTION_KEYS = keys(ROOT.get("actions"));
    public static final Set<String> TEMPLATE_KEYS = union(DATA_KEYS, HELPER_KEYS, ACTION_KEYS);
    public static final Set<String> COMPONENTS = keys(ROOT.get("components"));
    public static final Set<String> DIRECTIVES = Set.copyOf(strings(ROOT.get("directives")));
    public static final Set<String> CSS_VARS = Set.copyOf(strings(ROOT.get("cssVars")));
    public static final Set<String> GLOBAL_PARTS = Set.copyOf(strings(ROOT.get("globalParts")));

    private static final JsonNode EXPRESSION = ROOT.get("expression");
    public static final Set<String> EXPRESSION_GLOBALS =
            Set.copyOf(strings(EXPRESSION == null ? null : EXPRESSION.get("globals")));
    public static final Set<String> EVENT_LOCALS =
            Set.copyOf(strings(EXPRESSION == null ? null : EXPRESSION.get("eventLocals")));
    public static final Set<String> NATIVE_EVENTS =
            Set.copyOf(strings(EXPRESSION == null ? null : EXPRESSION.get("nativeEvents")));
    public static final Set<String> EVENT_MODIFIERS =
            Set.copyOf(strings(EXPRESSION == null ? null : EXPRESSION.get("eventModifiers")));
    public static final Set<String> MODEL_MODIFIERS =
            Set.copyOf(strings(EXPRESSION == null ? null : EXPRESSION.get("modelModifiers")));

    private static final JsonNode SKELETON = ROOT.get("skeleton");
    public static final String SKELETON_HTML =
            SKELETON == null ? "" : textOf(SKELETON.get("html"));
    public static final String SKELETON_CSS =
            SKELETON == null ? "" : textOf(SKELETON.get("css"));

    public static final Map<String, ComponentDef> COMPONENT_DEFS = componentDefs();
    public static final Set<String> PUBLIC_PARTS = publicParts();
    public static final Set<String> SLOT_NAMES = slotNames();
    public static final Map<String, Set<String>> SLOT_SCOPE = slotScope();
    public static final Map<String, String> ROUTES = routes();
    public static final Map<String, String> URLS = urls();

    private ThemeContract() {
    }

    /** 完整的生成规格：组件 / 类型 / 路由 / URL / 变量 / 指令。 */
    public static String promptSection() {
        StringBuilder text = new StringBuilder();
        text.append("\n【契约版本】").append(VERSION).append('\n');
        text.append("\n【组件（props 带 * 为必填；root slot 传内容即整体接管默认结构）】\n");
        for (Map.Entry<String, ComponentDef> entry : COMPONENT_DEFS.entrySet()) {
            ComponentDef def = entry.getValue();
            text.append('<').append(entry.getKey()).append(">\n");
            text.append("  props: ");
            if (def.props().isEmpty()) {
                text.append("-");
            } else {
                List<String> props = new ArrayList<>();
                for (String prop : def.props()) {
                    props.add(def.requiredProps().contains(prop) ? prop + "*" : prop);
                }
                text.append(String.join(", ", props));
            }
            text.append("\n  events: ")
                    .append(def.events().isEmpty() ? "-" : String.join(", ", def.events()));
            text.append("\n  slots: ");
            if (def.slots().isEmpty()) {
                text.append("-");
            } else {
                List<String> slots = new ArrayList<>();
                for (SlotDef slot : def.slots()) {
                    slots.add("#" + slot.name() + " " + slot.scope());
                }
                text.append(String.join("; ", slots));
            }
            text.append("\n  parts: ")
                    .append(def.parts().isEmpty() ? "-" : String.join(", ", def.parts()));
            text.append("\n  selfFetch: ").append(def.selfFetch() ? "是（放上去即请求）" : "否");
            if (def.example() != null && !def.example().isBlank()) {
                text.append("\n  example: ").append(def.example());
            }
            text.append('\n');
        }

        text.append("\n【数据类型】\n");
        JsonNode types = ROOT.get("types");
        if (types != null && types.isObject()) {
            for (Map.Entry<String, JsonNode> entry : types.properties()) {
                JsonNode value = entry.getValue();
                if (value.isObject()) {
                    List<String> fields = new ArrayList<>();
                    for (Map.Entry<String, JsonNode> field : value.properties()) {
                        fields.add(field.getKey() + ": " + textOf(field.getValue()));
                    }
                    text.append(entry.getKey()).append(" { ").append(String.join("; ", fields)).append(" }\n");
                } else {
                    text.append(entry.getKey()).append(" = ").append(textOf(value)).append('\n');
                }
            }
        }

        text.append("\n【路由（route.name / path / query）】\n");
        ROUTES.forEach((name, route) -> text.append(name).append(" -> ").append(route).append('\n'));

        text.append("\n【URL 形态】\n");
        URLS.forEach((name, pattern) -> text.append(name).append(": ").append(pattern).append('\n'));

        text.append("\n【CSS 变量】\n").append(String.join(", ", CSS_VARS)).append('\n');
        text.append("\n【模板指令】\n").append(String.join(", ", DIRECTIVES)).append('\n');
        text.append("\n【数据键（routes = 出现页面；writable = 可 v-model）】\n");
        JsonNode data = ROOT.get("data");
        if (data != null && data.isObject()) {
            for (Map.Entry<String, JsonNode> entry : data.properties()) {
                JsonNode value = entry.getValue();
                text.append(entry.getKey())
                        .append(" → routes: ")
                        .append(dataRoutes(value))
                        .append(value.get("writable") != null && value.get("writable").asBoolean()
                                ? "，writable"
                                : "")
                        .append('\n');
            }
        }
        text.append("\n【表达式全局（只读；不含 DOM / 网络）】\n")
                .append(String.join(", ", EXPRESSION_GLOBALS)).append('\n');
        text.append("\n【事件局部变量】\n")
                .append(String.join(", ", EVENT_LOCALS))
                .append("（只能在 @ / v-on 表达式中使用）\n");
        text.append("\n【组件上允许的原生事件】\n")
                .append(String.join(", ", NATIVE_EVENTS)).append('\n');
        text.append("\n【事件修饰符】\n")
                .append(String.join(", ", EVENT_MODIFIERS)).append('\n');
        text.append("\n【v-model 修饰符】\n")
                .append(String.join(", ", MODEL_MODIFIERS)).append('\n');
        text.append("\n【辅助函数】\n");
        ROOT.get("helpers").properties().forEach(entry ->
                text.append(entry.getKey()).append(entry.getValue().get("signature").asString()).append('\n'));
        text.append("\n【动作】\n");
        ROOT.get("actions").properties().forEach(entry ->
                text.append(entry.getKey()).append(entry.getValue().get("signature").asString()).append('\n'));
        return text.toString();
    }

    /** 数据键的可用页面：`all` 或 JSON 数组。 */
    private static String dataRoutes(JsonNode entry) {
        if (entry == null) {
            return "all";
        }
        JsonNode routes = entry.get("routes");
        if (routes == null || routes.isNull()) {
            return "all";
        }
        if (routes.isString()) {
            return routes.asString();
        }
        return routes.toString();
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("缺少或无法解析契约资源：" + RESOURCE
                    + "（请运行 pnpm sync:contract）", e);
        }
    }

    private static Set<String> keys(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(node.propertyNames()));
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isString()) {
                values.add(item.asString());
            }
        }
        return List.copyOf(values);
    }

    private static Set<String> union(Set<String>... sets) {
        Set<String> values = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private static Map<String, ComponentDef> componentDefs() {
        JsonNode components = ROOT.get("components");
        if (components == null || !components.isObject()) {
            return Map.of();
        }
        Map<String, ComponentDef> defs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : components.properties()) {
            JsonNode node = entry.getValue();
            defs.put(entry.getKey(), new ComponentDef(
                    strings(node.get("props")),
                    Set.copyOf(strings(node.get("requiredProps"))),
                    Set.copyOf(strings(node.get("events"))),
                    strings(node.get("parts")),
                    slots(node.get("slots")),
                    node.get("selfFetch") != null && node.get("selfFetch").asBoolean(),
                    node.get("example") == null ? "" : node.get("example").asString()));
        }
        return Map.copyOf(defs);
    }

    private static List<SlotDef> slots(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SlotDef> slots = new ArrayList<>();
        for (JsonNode item : node) {
            String name = item.get("name") == null ? "" : item.get("name").asString();
            List<String> fields = new ArrayList<>();
            JsonNode scope = item.get("scope");
            if (scope != null && scope.isArray()) {
                for (JsonNode field : scope) {
                    String fieldName = field.get("name") == null ? "" : field.get("name").asString();
                    String shape = field.get("shape") == null ? "unknown" : field.get("shape").asString();
                    fields.add(fieldName + ": " + shape);
                }
            }
            slots.add(new SlotDef(name, Set.copyOf(strings(item.get("provides"))), List.copyOf(fields)));
        }
        return List.copyOf(slots);
    }

    private static Set<String> publicParts() {
        Set<String> parts = new LinkedHashSet<>(GLOBAL_PARTS);
        for (ComponentDef def : COMPONENT_DEFS.values()) {
            parts.addAll(def.parts());
        }
        return Set.copyOf(parts);
    }

    private static Set<String> slotNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ComponentDef def : COMPONENT_DEFS.values()) {
            for (SlotDef slot : def.slots()) {
                names.add(slot.name());
            }
        }
        return Set.copyOf(names);
    }

    private static Map<String, Set<String>> slotScope() {
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        for (ComponentDef def : COMPONENT_DEFS.values()) {
            for (SlotDef slot : def.slots()) {
                scopes.computeIfAbsent(slot.name(), key -> new LinkedHashSet<>()).addAll(slot.provides());
            }
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        scopes.forEach((name, fields) -> result.put(name, Set.copyOf(fields)));
        return Map.copyOf(result);
    }

    private static Map<String, String> routes() {
        JsonNode routes = ROOT.get("routes");
        if (routes == null || !routes.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : routes.properties()) {
            JsonNode node = entry.getValue();
            String path = node.get("path") == null ? "" : node.get("path").asString();
            String query = node.get("query") == null ? "" : node.get("query").toString();
            result.put(entry.getKey(), query.isBlank() || "[]".equals(query) ? path : path + " " + query);
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> urls() {
        JsonNode urls = ROOT.get("urls");
        if (urls == null || !urls.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : urls.properties()) {
            result.put(entry.getKey(), textOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isString() ? node.asString() : node.toString();
    }

    public record ComponentDef(
            List<String> props,
            Set<String> requiredProps,
            Set<String> events,
            List<String> parts,
            List<SlotDef> slots,
            boolean selfFetch,
            String example) {
    }

    public record SlotDef(String name, Set<String> provides, List<String> scope) {
    }

    /** 供调试 / 测试读取完整 JSON；不存在时抛错。 */
    public static JsonNode root() {
        return ROOT;
    }
}
