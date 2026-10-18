package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.common.ErrorCodes;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前认证主体的读取工具（审计字段写入 / 控制器参数取用的统一出口）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** 当前操作者 id；未登录上下文（如登录流程自身）返回 null（审计字段允许为空） */
    public static String currentUserId() {
        return currentPrincipal().map(AuthPrincipal::userId).orElse(null);
    }

    /** 受保护接口中取当前主体；理论上必然存在（过滤器已认证），缺失视为会话失效 */
    public static AuthPrincipal requirePrincipal() {
        return currentPrincipal().orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REQUIRED, "未登录或会话已失效"));
    }
}
