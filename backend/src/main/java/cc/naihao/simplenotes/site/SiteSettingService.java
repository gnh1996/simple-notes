package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.SiteSetting;
import cc.naihao.simplenotes.repository.SiteSettingRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.AuthorResponse;
import cc.naihao.simplenotes.web.dto.NavItemResponse;
import cc.naihao.simplenotes.web.dto.SiteResponse;
import cc.naihao.simplenotes.web.dto.SiteSettingItem;
import cc.naihao.simplenotes.web.dto.SiteSettingsResponse;
import cc.naihao.simplenotes.web.dto.SocialLinkResponse;
import cc.naihao.simplenotes.web.dto.UpdateSiteSettingsRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点配置读写（docs/blog-runtime-theme.md §3.5 / §6.2）。
 *
 * <p>读写都经过 {@link SiteSettingCatalog}：库里没有的键取默认值，未注册的键写入即拒绝。
 * 值等于默认值时删除该行，保持“空表 = 全默认”。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SiteSettingService {

    private static final int MAX_JSON_BYTES = 64 * 1024;

    private final SiteSettingRepository repository;
    private final ObjectMapper objectMapper;

    /** 全部配置（含默认值），供管理端与页面组装使用 */
    @Transactional(readOnly = true)
    public Map<String, Object> resolved() {
        Map<String, String> stored = new LinkedHashMap<>();
        for (SiteSetting row : repository.findAll()) {
            stored.put(row.getSettingKey(), row.getSettingValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        SiteSettingCatalog.definitions().forEach((key, definition) -> {
            String raw = stored.get(key);
            result.put(key, parse(definition, raw == null ? definition.defaultValue() : raw));
        });
        return result;
    }

    @Transactional(readOnly = true)
    public SiteSettingsResponse adminView() {
        Map<String, Object> values = resolved();
        List<SiteSettingItem> items = SiteSettingCatalog.definitions().values().stream()
                // 系统托管键（theme.id）不在站点设置中编辑，切换主题只走主题管理的应用 / 回滚
                .filter(definition -> !SiteSettingCatalog.isManaged(definition.key()))
                .map(definition -> new SiteSettingItem(
                        definition.key(),
                        definition.type().name(),
                        definition.group(),
                        definition.label(),
                        definition.defaultValue(),
                        definition.publicReadable(),
                        definition.allowedValues(),
                        values.get(definition.key())))
                .toList();
        return new SiteSettingsResponse(currentVersion(), items);
    }

    /** 公开站点配置（页面注入与公开接口共用） */
    @Transactional(readOnly = true)
    public SiteResponse publicSite() {
        Map<String, Object> values = resolved();
        return new SiteResponse(
                text(values, "site.name"),
                text(values, "site.tagline"),
                text(values, "site.description"),
                blankToNull(text(values, "site.logoUrl")),
                blankToNull(text(values, "site.faviconUrl")),
                blankToNull(text(values, "site.baseUrl")),
                blankToNull(text(values, "site.icp")),
                blankToNull(text(values, "site.policeRecord")),
                new AuthorResponse(
                        text(values, "author.name"),
                        blankToNull(text(values, "author.avatarUrl")),
                        text(values, "author.bio")),
                social(values),
                nav(values),
                text(values, "theme.mode"),
                values.get("theme.overrides"),
                text(values, "footer.text"),
                number(values, "home.pageSize", 10));
    }

    @Transactional(readOnly = true)
    public String activeThemeId() {
        return text(resolved(), "theme.id");
    }

    /**
     * 批量保存：只处理提交的键，值等于默认值即删除该行。
     *
     * @throws ApiException 版本冲突 / 未知键 / 校验失败
     */
    @Transactional
    public SiteSettingsResponse update(UpdateSiteSettingsRequest request) {
        long current = currentVersion();
        if (request.version() == null || request.version() != current) {
            throw ApiException.versionConflict("站点配置已被修改，请刷新后重试");
        }
        String operator = SecurityUtils.currentUserId();
        Instant now = Instant.now();

        for (Map.Entry<String, Object> entry : request.values().entrySet()) {
            if (SiteSettingCatalog.isManaged(entry.getKey())) {
                throw ApiException.badRequest(
                        "配置项 " + entry.getKey() + " 由系统维护，请在主题管理中应用 / 回滚");
            }
            SettingDefinition definition = SiteSettingCatalog.find(entry.getKey())
                    .orElseThrow(() -> ApiException.badRequest("未知配置项：" + entry.getKey()));
            String stored = normalize(definition, entry.getValue());
            boolean isDefault = isDefault(definition, stored);

            repository.findById(definition.key()).ifPresentOrElse(row -> {
                if (isDefault) {
                    repository.delete(row);
                } else {
                    row.setSettingValue(stored);
                    row.setUpdateTime(now);
                    row.setUpdateBy(operator);
                }
            }, () -> {
                if (isDefault) {
                    return;
                }
                SiteSetting row = new SiteSetting();
                row.setSettingKey(definition.key());
                row.setSettingValue(stored);
                row.setCreateTime(now);
                row.setCreateBy(operator);
                repository.save(row);
            });
        }
        repository.flush();
        return adminView();
    }

    /** 乐观锁版本：取最近一次写入时间（毫秒）；空表为 0。 */
    @Transactional(readOnly = true)
    public long currentVersion() {
        return repository.findAll().stream()
                .map(row -> row.getUpdateTime() == null ? row.getCreateTime() : row.getUpdateTime())
                .filter(Objects::nonNull)
                .mapToLong(Instant::toEpochMilli)
                .max()
                .orElse(0L);
    }

    /** 内部写入单个键（主题激活用）：空值即删除该行。 */
    @Transactional
    public void setActiveTheme(String themeId) {
        Instant now = Instant.now();
        String operator = SecurityUtils.currentUserId();
        repository.findById("theme.id").ifPresentOrElse(row -> {
            if (themeId == null || themeId.isBlank()) {
                repository.delete(row);
                return;
            }
            row.setSettingValue(themeId);
            row.setUpdateTime(now);
            row.setUpdateBy(operator);
        }, () -> {
            if (themeId == null || themeId.isBlank()) {
                return;
            }
            SiteSetting row = new SiteSetting();
            row.setSettingKey("theme.id");
            row.setSettingValue(themeId);
            row.setCreateTime(now);
            row.setCreateBy(operator);
            repository.save(row);
        });
    }

    // ---- 内部 ----

    private Object parse(SettingDefinition definition, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (definition.type()) {
                case STRING -> raw;
                case NUMBER -> Long.parseLong(raw);
                case BOOLEAN -> Boolean.parseBoolean(raw);
                case JSON -> objectMapper.readValue(raw, Object.class);
            };
        } catch (RuntimeException e) {
            log.warn("配置项 {} 值无法解析，回落默认值：{}", definition.key(), e.getMessage());
            try {
                return switch (definition.type()) {
                    case STRING -> definition.defaultValue();
                    case NUMBER -> Long.parseLong(definition.defaultValue());
                    case BOOLEAN -> Boolean.parseBoolean(definition.defaultValue());
                    case JSON -> objectMapper.readValue(definition.defaultValue(), Object.class);
                };
            } catch (RuntimeException fallbackError) {
                return definition.defaultValue();
            }
        }
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<SocialLinkResponse> social(Map<String, Object> values) {
        JsonNode root = toJsonNode(values.get("social.links"));
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<SocialLinkResponse> links = new ArrayList<>();
        for (JsonNode node : items) {
            links.add(new SocialLinkResponse(
                    node.path("type").asText(null),
                    node.path("label").asText(null),
                    node.path("url").asText(null)));
        }
        return links;
    }

    private List<NavItemResponse> nav(Map<String, Object> values) {
        JsonNode root = toJsonNode(values.get("nav.items"));
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<Map.Entry<Integer, NavItemResponse>> ordered = new ArrayList<>();
        for (JsonNode node : items) {
            if (!node.path("visible").asBoolean(true)) {
                continue;
            }
            String key = node.path("key").asText("");
            String to = SiteSettingCatalog.navPath(key);
            if (to == null) {
                log.warn("导航配置含未知 key：{}", key);
                continue;
            }
            ordered.add(Map.entry(node.path("order").asInt(0),
                    new NavItemResponse(key, node.path("label").asText(key), to)));
        }
        ordered.sort(Map.Entry.comparingByKey());
        return ordered.stream().map(Map.Entry::getValue).toList();
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(objectMapper.writeValueAsString(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String normalize(SettingDefinition definition, Object value) {
        if (value == null) {
            return null;
        }
        String stored;
        switch (definition.type()) {
            case STRING -> {
                stored = String.valueOf(value);
                int maxLength = definition.maxLength() == null ? 2000 : definition.maxLength();
                if (stored.length() > maxLength) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 超出长度上限");
                }
                if (definition.allowedValues() != null
                        && !definition.allowedValues().contains(stored)) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 取值不在允许范围");
                }
            }
            case NUMBER -> {
                long number;
                try {
                    number = value instanceof Number n ? n.longValue()
                            : Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 需要是数字");
                }
                if (definition.min() != null && number < definition.min()) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 小于下限");
                }
                if (definition.max() != null && number > definition.max()) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 超出上限");
                }
                stored = Long.toString(number);
            }
            case BOOLEAN -> stored = Boolean.toString(
                    value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)));
            case JSON -> {
                if ("theme.overrides".equals(definition.key())) {
                    SiteSettingCatalog.validateThemeOverrides(value);
                }
                try {
                    stored = objectMapper.writeValueAsString(value);
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 不是合法 JSON");
                }
                if (stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                    throw ApiException.badRequest("配置项 " + definition.key() + " 超出体积上限");
                }
            }
            default -> throw new IllegalStateException("未处理的值类型：" + definition.type());
        }
        return stored;
    }

    private boolean isDefault(SettingDefinition definition, String stored) {
        if (stored == null) {
            return true;
        }
        if (definition.type() == SettingType.JSON) {
            JsonNode value = toJsonNode(parse(definition, stored));
            JsonNode defaultValue = toJsonNode(parse(definition, definition.defaultValue()));
            return value != null && value.equals(defaultValue);
        }
        return stored.equals(definition.defaultValue());
    }
}
