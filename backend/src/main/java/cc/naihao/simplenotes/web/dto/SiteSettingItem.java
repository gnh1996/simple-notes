package cc.naihao.simplenotes.web.dto;

import java.util.List;

/**
 * 管理端配置项视图：含类型 / 分组 / 默认值等元信息（元信息来自代码侧注册表，不入库）。
 */
public record SiteSettingItem(
        String key,
        String type,
        String group,
        String label,
        String defaultValue,
        boolean publicReadable,
        List<String> allowedValues,
        Object value) {
}
