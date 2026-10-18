package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的密码（需旧密码；成功后撤销除当前外的全部会话，docs/auth-rbac.md §5.4）。
 */
public record PasswordChangeRequest(
        @NotBlank(message = "旧密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String newPassword) {
}
