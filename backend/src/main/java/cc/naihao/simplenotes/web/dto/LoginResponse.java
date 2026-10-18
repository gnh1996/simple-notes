package cc.naihao.simplenotes.web.dto;

/**
 * 登录响应：JWT + 身份信息（三端统一，docs/auth-rbac.md §5.2）。
 */
public record LoginResponse(String token, UserSummary user) {
}
