package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 标签管理列表项：三层使用量（随手记 / 当前文章草稿 / 已发布快照）。 */
@Schema(requiredProperties = { "id", "name", "noteCount", "postCount", "publishedCount", "createTime" })
public record TagAdminSummary(
        String id,
        String name,
        long noteCount,
        long postCount,
        long publishedCount,
        Instant createTime,
        Instant updateTime) {
}
