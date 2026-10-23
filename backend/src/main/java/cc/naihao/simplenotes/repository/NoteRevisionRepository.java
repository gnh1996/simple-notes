package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.NoteRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 发布快照仓储：不可变事实表，只新增与读取。 */
public interface NoteRevisionRepository extends JpaRepository<NoteRevision, String> {

    Optional<NoteRevision> findTopByNoteIdOrderByRevisionNoDesc(String noteId);

    List<NoteRevision> findByNoteIdOrderByRevisionNoDesc(String noteId);

    boolean existsByCoverAttachmentId(String coverAttachmentId);
}
