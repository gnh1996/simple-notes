package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.NoteFolder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 记事本文件夹仓储（v2.0 树形、按所有者隔离）。 */
public interface NoteFolderRepository extends JpaRepository<NoteFolder, String> {

    List<NoteFolder> findByOwnerIdOrderBySortAscCreateTimeDesc(String ownerId);

    Optional<NoteFolder> findByIdAndOwnerId(String id, String ownerId);

    boolean existsByOwnerIdAndParentIdIsNullAndNameIgnoreCase(String ownerId, String name);

    boolean existsByOwnerIdAndParentIdIsNullAndNameIgnoreCaseAndIdNot(String ownerId, String name, String id);

    boolean existsByOwnerIdAndParentIdAndNameIgnoreCase(String ownerId, String parentId, String name);

    boolean existsByOwnerIdAndParentIdAndNameIgnoreCaseAndIdNot(String ownerId, String parentId, String name,
            String id);

    /** 是否存在未删除的子文件夹（实体级 @SQLRestriction 已排除已删除）。 */
    boolean existsByOwnerIdAndParentId(String ownerId, String parentId);
}
