package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.SysRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 角色摘要（me 的 roles / 用户列表的 roles）。
 */
@Schema(requiredProperties = { "id", "code", "name" })
public record RoleBrief(String id, String code, String name) {

    public static RoleBrief from(SysRole role) {
        return new RoleBrief(role.getId(), role.getCode(), role.getName());
    }
}
