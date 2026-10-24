package cc.naihao.simplenotes.site;

import java.util.List;

/**
 * 单个站点配置项的定义（代码侧契约，不入库）。
 *
 * @param key             配置键，属于 L0 冻结契约，改名即破坏性变更
 * @param type            值类型
 * @param group           管理端分组
 * @param label           展示名
 * @param defaultValue    默认值（空表等价于全部默认值）
 * @param publicReadable  是否随公开接口 / 页面注入下发
 * @param allowedValues   枚举候选（仅 STRING 使用，null = 不限）
 * @param min             数值下限（仅 NUMBER 使用）
 * @param max             数值上限（仅 NUMBER 使用）
 * @param maxLength       字符串长度上限（仅 STRING 使用）
 */
public record SettingDefinition(
        String key,
        SettingType type,
        String group,
        String label,
        String defaultValue,
        boolean publicReadable,
        List<String> allowedValues,
        Long min,
        Long max,
        Integer maxLength) {

    public static SettingDefinition string(String key, String group, String label, String defaultValue,
            boolean publicReadable) {
        return new SettingDefinition(key, SettingType.STRING, group, label, defaultValue, publicReadable,
                null, null, null, 2000);
    }

    public static SettingDefinition string(String key, String group, String label, String defaultValue,
            boolean publicReadable, List<String> allowedValues) {
        return new SettingDefinition(key, SettingType.STRING, group, label, defaultValue, publicReadable,
                allowedValues, null, null, 2000);
    }

    public static SettingDefinition number(String key, String group, String label, long defaultValue,
            boolean publicReadable, long min, long max) {
        return new SettingDefinition(key, SettingType.NUMBER, group, label, Long.toString(defaultValue),
                publicReadable, null, min, max, null);
    }

    public static SettingDefinition json(String key, String group, String label, String defaultValue,
            boolean publicReadable) {
        return new SettingDefinition(key, SettingType.JSON, group, label, defaultValue, publicReadable,
                null, null, null, null);
    }
}
