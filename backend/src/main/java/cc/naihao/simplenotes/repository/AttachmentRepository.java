package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.Attachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 附件元数据仓储。 */
public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findAllByOrderByCreateTimeDesc();

    List<Attachment> findAllByOwnerIdOrderByCreateTimeDesc(String ownerId);

    Optional<Attachment> findByIdAndOwnerId(String id, String ownerId);
}
