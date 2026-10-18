package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.ClientType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登录请求（三端统一，docs/auth-rbac.md §5.2）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        @NotNull(message = "端不能为空") ClientType client) {
}
