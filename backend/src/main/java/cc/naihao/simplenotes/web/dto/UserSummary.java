package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.SysUser;

/**
 * 用户摘要（登录响应 / me / 个人中心）。
 */
public record UserSummary(String id, String username, String nickname, String email) {

    public static UserSummary from(SysUser user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getNickname(), user.getEmail());
    }
}
