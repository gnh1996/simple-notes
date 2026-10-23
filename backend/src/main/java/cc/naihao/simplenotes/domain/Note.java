package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 内容主表（docs/content-model.md §3.1）：随手记与博客文章共用，kind 区分。
 *
 * <p>{@code content} 等字段保存当前草稿；公开博客读取 {@code publishedRevisionId} 指向的
 * {@link NoteRevision} 快照，普通保存不会影响线上内容。
 */
@Entity
@Table(name = "note")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class Note extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private NoteKind kind;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** 博客摘要：仅 POST 使用；发布时必填 */
    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    /** 仅 NOTE 使用的文件夹 */
    @Column(name = "folder_id", length = 36)
    private String folderId;

    /** 1 = 置顶；0 = 普通（运营字段，不参与发布快照） */
    @Column(name = "pinned", nullable = false)
    private short pinned = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private NoteStatus status = NoteStatus.ACTIVE;

    /** 仅 POST：公开 URL 标识；首次发布后不可修改 */
    @Column(name = "slug", length = 200)
    private String slug;

    /** 仅 POST：封面附件 */
    @Column(name = "cover_attachment_id", length = 36)
    private String coverAttachmentId;

    /** 仅 POST：DRAFT / PUBLISHED / UNPUBLISHED */
    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", length = 16)
    private PublishStatus publishStatus;

    /** 当前公开快照指针；公开博客只读它 */
    @Column(name = "published_revision_id", length = 36)
    private String publishedRevisionId;

    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    @Column(name = "last_published_at")
    private Instant lastPublishedAt;

    /** 草稿最近修改时间；用于 hasUnpublishedChanges */
    @Column(name = "draft_modified_at")
    private Instant draftModifiedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
