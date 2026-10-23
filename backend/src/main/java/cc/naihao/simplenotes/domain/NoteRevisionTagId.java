package cc.naihao.simplenotes.domain;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** {@link NoteRevisionTag} 复合主键：(revision_id, tag_id)。 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class NoteRevisionTagId implements Serializable {

    private String revisionId;

    private String tagId;
}
