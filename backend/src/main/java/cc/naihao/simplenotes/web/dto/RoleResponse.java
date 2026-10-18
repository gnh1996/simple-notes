package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 角色列表项（管理端）：{@code permissionIds} = 已授权节点 id（角色编辑器回显勾选态）。
 */
@Schema(requiredProperties = { "id", "code", "name", "builtin", "sort", "status", "permissionIds", "createTime" })
public record RoleResponse(
        String id,
        String code,
        String name,
        String description,
        boolean builtin,
        int sort,
        short status,
        List<String> permissionIds,
        Instant createTime) {
}
