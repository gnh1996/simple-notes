package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户摘要（登录响应 / me / 个人中心）。
 */
@Schema(requiredProperties = { "id", "username", "nickname" })
public record UserSummary(String id, String username, String nickname, String email) {

    public static UserSummary from(SysUser user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getNickname(), user.getEmail());
    }
}
