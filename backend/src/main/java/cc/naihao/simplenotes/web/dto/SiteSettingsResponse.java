package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 管理端站点配置读取结果；{@code version} 用于批量保存的乐观锁。 */
@Schema(requiredProperties = { "version", "items" })
public record SiteSettingsResponse(long version, List<SiteSettingItem> items) {
}
