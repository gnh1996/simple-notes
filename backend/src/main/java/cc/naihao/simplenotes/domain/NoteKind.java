package cc.naihao.simplenotes.domain;

/**
 * 内容类型（docs/content-model.md §2）：笔记与博客文章共用 note 表，以 kind 区分。
 *
 * <p>创建后不可改；唯一例外是显式 promote（NOTE → POST）。
 */
public enum NoteKind {
    NOTE,
    POST
}
