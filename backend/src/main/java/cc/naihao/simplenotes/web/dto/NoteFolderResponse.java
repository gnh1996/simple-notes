package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 记事本文件夹（v1 单层）。
 *
 * <p>{@code noteCount} 是该文件夹下未删除随手记的数量；只用于列表展示，不参与编辑。
 */
@Schema(requiredProperties = { "id", "name", "sort", "noteCount", "createTime" })
public record NoteFolderResponse(String id, String name, int sort, long noteCount, Instant createTime,
        Instant updateTime) {
}
