package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建角色（内置角色码不可复用，唯一约束兜底）。
 */
public record CreateRoleRequest(
        @NotBlank(message = "角色码不能为空")
        @Size(max = 64, message = "角色码最长 64 个字符")
        @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "角色码需为大写字母 / 数字 / 下划线，且以字母开头")
        String code,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 32, message = "角色名称最长 32 个字符")
        String name,

        @Size(max = 200, message = "描述最长 200 个字符")
        String description,

        Integer sort) {
}
