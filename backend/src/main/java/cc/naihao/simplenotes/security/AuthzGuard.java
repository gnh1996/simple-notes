package cc.naihao.simplenotes.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 方法级权限校验入口（{@code @PreAuthorize("@authz.can('admin:user')")}）。
 *
 * <p>超管短路（docs/auth-rbac.md §4）：持有超管角色 → 不受任何权限检查（含未来新增权限，自动放行）。
 * 注解里的码为字符串字面量，手写偏差由启动对账兜底（扫描 {@code @PreAuthorize} 校验码已声明于
 * {@link Perms}，缺失则启动失败）。
 *
 * <p>Bean 名固定为 {@code authz}，与注解中的 SpEL 引用一致。
 */
@Component("authz")
public class AuthzGuard {

    /** 当前会话是否具备指定权限码（超管直通） */
    public boolean can(String code) {
        AuthPrincipal principal = principal();
        return principal != null && principal.can(code);
    }

    /** 任一权限码满足即可（如菜单查询：{@code admin:role} 或 {@code admin:menu}） */
    public boolean canAny(String... codes) {
        AuthPrincipal principal = principal();
        if (principal == null) {
            return false;
        }
        if (principal.root()) {
            return true;
        }
        for (String code : codes) {
            if (principal.codes().contains(code)) {
                return true;
            }
        }
        return false;
    }

    private AuthPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        return null;
    }
}
