package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 不可变发布快照（docs/content-model.md §3.2）：每次发布 / 更新发布新增一行。
 *
 * <p>只 INSERT，不 UPDATE / DELETE；不参与软删除，因此不继承 {@link BaseEntity}。
 */
@Entity
@Table(name = "note_revision")
@Getter
@Setter
public class NoteRevision implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "note_id", length = 36, nullable = false, updatable = false)
    private String noteId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;

    @Column(name = "title", length = 200, nullable = false, updatable = false)
    private String title;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "summary", length = 500, nullable = false, updatable = false)
    private String summary;

    @Column(name = "cover_attachment_id", length = 36, updatable = false)
    private String coverAttachmentId;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    @PrePersist
    void onCreate() {
        if (createTime == null) {
            createTime = Instant.now();
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
