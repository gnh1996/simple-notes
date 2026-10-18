package cc.naihao.simplenotes.common;

/**
 * 错误语义码（docs/auth-rbac.md §8）。前后端以语义码对接，HTTP 状态码仅表达大致类别。
 */
public final class ErrorCodes {

    /** 未登录 / 会话失效（401） */
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    /** 已登录但无权限（403） */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    /** 登录凭据错误（401） */
    public static final String LOGIN_CREDENTIALS_INVALID = "LOGIN_CREDENTIALS_INVALID";
    /** 账号被禁用（403） */
    public static final String LOGIN_USER_DISABLED = "LOGIN_USER_DISABLED";
    /** 无该端登录权限（403） */
    public static final String LOGIN_ENTRY_DENIED = "LOGIN_ENTRY_DENIED";
    /** 登录限流（429） */
    public static final String LOGIN_RATE_LIMITED = "LOGIN_RATE_LIMITED";
    /** 请求参数校验失败（400） */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** 资源不存在（404） */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 请求方式不支持（405） */
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    /** 与现有数据冲突（409）：登录名 / 角色码重复、角色仍被占用等 */
    public static final String CONFLICT = "CONFLICT";
    /** 未预期的服务端错误（500） */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
