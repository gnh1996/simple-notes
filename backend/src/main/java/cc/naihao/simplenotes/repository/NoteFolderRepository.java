package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.NoteFolder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 记事本文件夹仓储（v1 单层、按所有者隔离）。 */
public interface NoteFolderRepository extends JpaRepository<NoteFolder, String> {

    List<NoteFolder> findByOwnerIdOrderBySortAscCreateTimeDesc(String ownerId);

    Optional<NoteFolder> findByIdAndOwnerId(String id, String ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(String ownerId, String name);

    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(String ownerId, String name, String id);
}
