package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 标签仓储：名称大小写不敏感唯一。 */
public interface TagRepository extends JpaRepository<Tag, String> {

    Optional<Tag> findByNameIgnoreCase(String name);

    List<Tag> findByNameInIgnoreCase(List<String> names);

    List<Tag> findAllByOrderByNameAsc();
}
