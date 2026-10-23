package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 附件 / 媒体库条目；url 为稳定站内 URL。 */
@Schema(requiredProperties = { "id", "url", "originalName", "mimeType", "sizeBytes", "createTime" })
public record AttachmentResponse(
        String id,
        String url,
        String originalName,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        String altText,
        Instant createTime) {
}
