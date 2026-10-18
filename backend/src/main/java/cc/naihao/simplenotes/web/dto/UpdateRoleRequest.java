package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑角色（名称 / 描述 / 排序 / 状态；内置角色拒绝）。
 */
public record UpdateRoleRequest(
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 32, message = "角色名称最长 32 个字符")
        String name,

        @Size(max = 200, message = "描述最长 200 个字符")
        String description,

        @NotNull(message = "排序不能为空")
        Integer sort,

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态取值 0 或 1")
        @Max(value = 1, message = "状态取值 0 或 1")
        Integer status) {
}
