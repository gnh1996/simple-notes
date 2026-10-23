package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 发布快照标签（docs/content-model.md §3.3）：发布时从 {@link NoteTag} 复制，不可变。
 */
@Entity
@Table(name = "note_revision_tag")
@IdClass(NoteRevisionTagId.class)
@Getter
@Setter
public class NoteRevisionTag implements Persistable<NoteRevisionTagId> {

    @Id
    @Column(name = "revision_id", length = 36, nullable = false, updatable = false)
    private String revisionId;

    @Id
    @Column(name = "tag_id", length = 36, nullable = false, updatable = false)
    private String tagId;

    @Transient
    private boolean isNew = true;

    public NoteRevisionTag() {
    }

    public NoteRevisionTag(String revisionId, String tagId) {
        this.revisionId = revisionId;
        this.tagId = tagId;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public NoteRevisionTagId getId() {
        return new NoteRevisionTagId(revisionId, tagId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
