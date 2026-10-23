package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.PublishStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 内容仓储（docs/content-model.md §3.1）。实体带 {@code @SQLRestriction("delete_flag = 0")}，
 * 普通查询默认排除回收站；回收站通过专用原生 SQL 读取。
 */
public interface NoteRepository extends JpaRepository<Note, String> {

    Optional<Note> findByIdAndKind(String id, NoteKind kind);

    Optional<Note> findByKindAndSlugAndPublishStatus(NoteKind kind, String slug, PublishStatus publishStatus);

    boolean existsByCoverAttachmentId(String coverAttachmentId);

    List<Note> findByFolderIdAndKind(String folderId, NoteKind kind);
}
