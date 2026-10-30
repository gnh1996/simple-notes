package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;

/** 公开博客的时间字段排序白名单（docs/content-model.md §6.4）。 */
public enum BlogSortField {
    DISPLAY_AT("displayAt"),
    FIRST_PUBLISHED_AT("firstPublishedAt"),
    LAST_PUBLISHED_AT("lastPublishedAt");

    private final String value;

    BlogSortField(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static BlogSortField from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DISPLAY_AT;
        }
        for (BlogSortField field : values()) {
            if (field.value.equals(raw)) {
                return field;
            }
        }
        throw ApiException.badRequest("sortBy 仅支持 displayAt / firstPublishedAt / lastPublishedAt");
    }
}
