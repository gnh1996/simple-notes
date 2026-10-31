package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 桌面端同步索引项（docs/desktop-sync.md §3.2）。
 *
 * <p>只包含轻量字段与正文摘要，不含 {@code content}；正文由详情接口按需拉取。
 * {@code deleted = true} 表示该行在回收站中，作为墓碑下发给桌面端。
 */
@Schema(requiredProperties = { "id", "kind", "title", "pinned", "version", "updateTime", "deleted" })
public record SyncItem(
        String id,
        String kind,
        String title,
        String folderId,
        boolean pinned,
        String publishStatus,
        long version,
        Instant updateTime,
        boolean deleted) {
}
