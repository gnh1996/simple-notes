package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SiteThemeVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 不可变主题版本仓储：只新增与读取，不提供更新 / 删除入口。 */
public interface SiteThemeVersionRepository extends JpaRepository<SiteThemeVersion, String> {

    List<SiteThemeVersion> findByThemeIdOrderByVersionNoDesc(String themeId);

    Optional<SiteThemeVersion> findTopByThemeIdOrderByVersionNoDesc(String themeId);

    Optional<SiteThemeVersion> findByIdAndThemeId(String id, String themeId);
}
