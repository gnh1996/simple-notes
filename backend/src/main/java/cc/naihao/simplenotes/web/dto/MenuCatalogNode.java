package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.MenuType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 权限目录节点（管理端完整树，角色编辑器 / 菜单管理共用）：
 * 含全部客户端的 ENTRY 根、含停用节点与 ACTION；结构性字段只读。
 */
@Schema(requiredProperties = { "id", "client", "type", "code", "name", "sort", "status", "children" })
public record MenuCatalogNode(
        String id,
        String parentId,
        ClientType client,
        MenuType type,
        String code,
        String name,
        int sort,
        short status,
        List<MenuCatalogNode> children) {
}
