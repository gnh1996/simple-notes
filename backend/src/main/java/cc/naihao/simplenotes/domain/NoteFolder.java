package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 记事本文件夹（docs/content-model.md §3.4）：v1 单层，按所有者隔离。
 */
@Entity
@Table(name = "note_folder")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class NoteFolder extends BaseEntity {

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "sort", nullable = false)
    private int sort = 0;
}
