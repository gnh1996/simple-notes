package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 当前草稿标签（docs/content-model.md §3.3）：保存时全量替换，物理删除。
 */
@Entity
@Table(name = "note_tag")
@IdClass(NoteTagId.class)
@Getter
@Setter
public class NoteTag implements Persistable<NoteTagId> {

    @Id
    @Column(name = "note_id", length = 36, nullable = false, updatable = false)
    private String noteId;

    @Id
    @Column(name = "tag_id", length = 36, nullable = false, updatable = false)
    private String tagId;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    public NoteTag() {
    }

    public NoteTag(String noteId, String tagId, String createBy) {
        this.noteId = noteId;
        this.tagId = tagId;
        this.createBy = createBy;
    }

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
    public NoteTagId getId() {
        return new NoteTagId(noteId, tagId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
