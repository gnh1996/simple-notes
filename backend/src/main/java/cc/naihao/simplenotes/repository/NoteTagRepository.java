package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.NoteTag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 当前草稿标签关联：全量替换式更新，物理删除。 */
public interface NoteTagRepository extends JpaRepository<NoteTag, cc.naihao.simplenotes.domain.NoteTagId> {

    List<NoteTag> findByNoteId(String noteId);

    List<NoteTag> findByNoteIdIn(Collection<String> noteIds);

    Optional<NoteTag> findByNoteIdAndTagId(String noteId, String tagId);

    List<NoteTag> findByTagId(String tagId);
}
