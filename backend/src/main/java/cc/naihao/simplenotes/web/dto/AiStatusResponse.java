package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 可用性（docs/ai.md §2.1）：连接信息缺失时为 false，前端据此把入口置灰。
 * 不返回模型 / 网关地址，避免把配置暴露给非必要页面。
 */
@Schema(requiredProperties = { "enabled" })
public record AiStatusResponse(
        boolean enabled) {
}
