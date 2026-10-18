package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.SysRole;

/**
 * 角色摘要（me 的 roles / 用户列表的 roles）。
 */
public record RoleBrief(String id, String code, String name) {

    public static RoleBrief from(SysRole role) {
        return new RoleBrief(role.getId(), role.getCode(), role.getName());
    }
}
