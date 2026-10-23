package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 记事本文件夹（v1 单层）。 */
@Schema(requiredProperties = { "id", "name", "sort", "createTime" })
public record NoteFolderResponse(String id, String name, int sort, Instant createTime) {
}
