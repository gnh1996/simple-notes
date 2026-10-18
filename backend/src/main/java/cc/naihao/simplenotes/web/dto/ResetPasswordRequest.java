package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置他人密码（超管角色持有者拒绝）。
 */
public record ResetPasswordRequest(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String newPassword) {
}
