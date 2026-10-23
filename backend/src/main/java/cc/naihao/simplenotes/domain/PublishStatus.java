package cc.naihao.simplenotes.domain;

/**
 * 博客文章发布状态（docs/content-model.md §4.2）：仅 kind=POST 使用，笔记必须为 null。
 */
public enum PublishStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED
}
