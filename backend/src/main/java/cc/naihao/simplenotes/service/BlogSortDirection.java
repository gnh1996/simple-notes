package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;

/** 公开博客的时间排序方向；只作用于时间字段，不翻转置顶。 */
public enum BlogSortDirection {
    ASC("asc"),
    DESC("desc");

    private final String value;

    BlogSortDirection(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean ascending() {
        return this == ASC;
    }

    public static BlogSortDirection from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DESC;
        }
        for (BlogSortDirection direction : values()) {
            if (direction.value.equals(raw)) {
                return direction;
            }
        }
        throw ApiException.badRequest("direction 仅支持 asc / desc");
    }
}
