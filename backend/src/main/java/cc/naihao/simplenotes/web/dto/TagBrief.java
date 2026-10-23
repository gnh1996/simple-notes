package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 标签摘要（笔记 / 文章 / 公开博客共用）。 */
@Schema(requiredProperties = { "id", "name" })
public record TagBrief(String id, String name) {
}
