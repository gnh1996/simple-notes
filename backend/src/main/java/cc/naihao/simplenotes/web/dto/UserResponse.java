package cc.naihao.simplenotes.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 用户列表项（管理端）。{@code root} = 是否持有超管角色（UI 据此禁用删除 / 禁用 / 改角色 / 重置密码）。
 */
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
