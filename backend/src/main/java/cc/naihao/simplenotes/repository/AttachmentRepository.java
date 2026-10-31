package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.domain.StorageMode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 附件元数据仓储。 */
public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findAllByOrderByCreateTimeDesc();

    List<Attachment> findAllByOwnerIdOrderByCreateTimeDesc(String ownerId);

    Optional<Attachment> findByIdAndOwnerId(String id, String ownerId);

    /** 内部 presign / 直链校验用：storage_key 全表唯一。 */
    Optional<Attachment> findByStorageKey(String storageKey);

    long countByStorageMode(StorageMode storageMode);
}
