package cc.naihao.simplenotes.domain;

/**
 * 内容工作区状态（docs/content-model.md §3.1）：仅笔记使用归档；文章下架用 {@link PublishStatus}。
 */
public enum NoteStatus {
    ACTIVE,
    ARCHIVED
}
