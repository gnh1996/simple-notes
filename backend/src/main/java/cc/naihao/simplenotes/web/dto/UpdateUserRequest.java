package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑用户（昵称 / 邮箱 / 状态；用户名不可改）。
 */
public record UpdateUserRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱最长 128 个字符")
        String email,

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态取值 0 或 1")
        @Max(value = 1, message = "状态取值 0 或 1")
        Integer status) {
}
