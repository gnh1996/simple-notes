package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 分配角色（全量替换；独立于编辑用户，docs/auth-rbac.md §8）。
 */
public record AssignRolesRequest(@NotNull(message = "角色集合不能为 null") List<String> roleIds) {
}
