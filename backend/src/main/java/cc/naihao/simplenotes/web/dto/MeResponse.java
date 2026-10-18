package cc.naihao.simplenotes.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 当前身份（docs/auth-rbac.md §8）：身份 + root + entries + codes + roles；
 * {@code menus} 仅 ADMIN 端返回（可见菜单树，已按权限过滤；超管返回全量）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeResponse(
        UserSummary user,
        boolean root,
        List<String> entries,
        List<String> codes,
        List<RoleBrief> roles,
        List<MenuNode> menus) {
}
