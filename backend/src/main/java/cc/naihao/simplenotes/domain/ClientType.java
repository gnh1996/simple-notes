package cc.naihao.simplenotes.domain;

import java.util.Locale;

/**
 * 端标识（接口与库中大写；权限码中小写，如 {@code entry:admin}）。
 *
 * <p>见 docs/auth-rbac.md §2「端 Client」。
 */
public enum ClientType {

    ADMIN,
    BLOG,
    DESKTOP;

    /** 该端入口权限码：{@code entry:admin} / {@code entry:blog} / {@code entry:desktop} */
    public String entryCode() {
        return "entry:" + name().toLowerCase(Locale.ROOT);
    }

    /** 该端请求路径域前缀：{@code /api/admin} 等 */
    public String pathPrefix() {
        return "/api/" + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 从请求路径推断所属端（会话端一致性校验用），非端内路径返回 {@code null}。
     */
    public static ClientType fromPath(String requestPath) {
        for (ClientType client : values()) {
            String prefix = client.pathPrefix();
            if (requestPath.equals(prefix) || requestPath.startsWith(prefix + "/")) {
                return client;
            }
        }
        return null;
    }
}
