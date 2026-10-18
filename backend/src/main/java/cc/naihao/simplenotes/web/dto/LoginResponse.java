package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录响应：JWT + 身份信息（三端统一，docs/auth-rbac.md §5.2）。
 */
@Schema(requiredProperties = { "token", "user" })
public record LoginResponse(String token, UserSummary user) {
}
