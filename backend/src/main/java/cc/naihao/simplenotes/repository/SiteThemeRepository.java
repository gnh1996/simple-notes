package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SiteTheme;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 主题身份仓储（docs/blog-runtime-theme.md §6.1）。 */
public interface SiteThemeRepository extends JpaRepository<SiteTheme, String> {

    List<SiteTheme> findAllByOrderByCreateTimeAsc();

    Optional<SiteTheme> findByNameIgnoreCase(String name);
}
