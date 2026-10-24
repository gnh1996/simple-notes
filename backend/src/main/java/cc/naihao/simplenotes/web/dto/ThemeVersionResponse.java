package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 主题版本详情（管理端编辑 / diff 用）。 */
@Schema(requiredProperties = {
        "id", "themeId", "versionNo", "format", "contract", "html", "css", "createTime" })
public record ThemeVersionResponse(
        String id,
        String themeId,
        int versionNo,
        int format,
        int contract,
        String html,
        String css,
        String createBy,
        Instant createTime) {
}
