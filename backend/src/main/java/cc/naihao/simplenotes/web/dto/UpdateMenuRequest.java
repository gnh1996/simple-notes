package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑菜单节点（仅改名 / 调序 / 启停；结构性字段与增删不提供，docs/auth-rbac.md §3.4）。
 */
public record UpdateMenuRequest(
        @NotBlank(message = "节点名称不能为空")
        @Size(max = 64, message = "节点名称最长 64 个字符")
        String name,

        @NotNull(message = "排序不能为空")
        Integer sort,

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态取值 0 或 1")
        @Max(value = 1, message = "状态取值 0 或 1")
        Integer status) {
}
