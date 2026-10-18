package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建用户（登录名 + 初始密码 + 昵称；角色另行分配，docs/auth-rbac.md §6.2）。
 */
public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 32, message = "用户名最长 32 个字符")
        @Pattern(regexp = "(?i)[a-z0-9][a-z0-9_-]{2,31}", message = "用户名需为 3-32 位字母 / 数字 / 下划线 / 中划线，且以字母或数字开头")
        String username,

        @NotBlank(message = "初始密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱最长 128 个字符")
        String email) {
}
