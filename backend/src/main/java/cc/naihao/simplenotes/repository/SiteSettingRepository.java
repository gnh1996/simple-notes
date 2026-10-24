package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SiteSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/** 站点配置 KV 仓储：按 key 读取 / 写入，无软删除。 */
public interface SiteSettingRepository extends JpaRepository<SiteSetting, String> {
}
