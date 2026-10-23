package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.NoteRevisionTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 发布快照标签关联：不可变，只新增与读取。 */
public interface NoteRevisionTagRepository
        extends JpaRepository<NoteRevisionTag, cc.naihao.simplenotes.domain.NoteRevisionTagId> {

    List<NoteRevisionTag> findByRevisionId(String revisionId);

    List<NoteRevisionTag> findByRevisionIdIn(Collection<String> revisionIds);
}
