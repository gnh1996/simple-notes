package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 用户列表项（管理端）。{@code root} = 是否持有超管角色（UI 据此禁用删除 / 禁用 / 改角色 / 重置密码）。
 */
@Schema(requiredProperties = { "id", "username", "nickname", "status", "root", "roles", "createTime" })
public record UserResponse(
        String id,
        String username,
        String nickname,
        String email,
        short status,
        Instant lastLoginTime,
        boolean root,
        List<RoleBrief> roles,
        Instant createTime) {
}
