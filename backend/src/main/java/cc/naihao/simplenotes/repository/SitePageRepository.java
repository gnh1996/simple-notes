package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SitePage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 独立页面仓储（保存即线上）。 */
public interface SitePageRepository extends JpaRepository<SitePage, String> {

    Optional<SitePage> findBySlugIgnoreCase(String slug);

    List<SitePage> findAllByOrderBySortAscCreateTimeAsc();
}
