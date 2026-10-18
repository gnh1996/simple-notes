package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 角色授权（全量替换权限节点集合；内置角色拒绝）。
 */
public record GrantPermissionsRequest(@NotNull(message = "权限节点集合不能为 null") List<String> menuIds) {
}
