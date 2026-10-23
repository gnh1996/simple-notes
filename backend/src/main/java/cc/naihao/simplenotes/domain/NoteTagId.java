package cc.naihao.simplenotes.domain;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** {@link NoteTag} 复合主键：(note_id, tag_id)。 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class NoteTagId implements Serializable {

    private String noteId;

    private String tagId;
}
