package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 同步索引分页（docs/desktop-sync.md §3.2）。
 *
 * <p>{@code nextCursor} 为不透明游标：客户端原样回传即可增量拉取；为空表示还没有数据。
 */
@Schema(requiredProperties = { "items", "hasMore" })
public record SyncPage(List<SyncItem> items, String nextCursor, boolean hasMore) {
}
