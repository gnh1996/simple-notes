package cc.naihao.simplenotes.security;

import java.util.Set;

/**
 * 用户授权快照（docs/auth-rbac.md §5.3）：用户 → 角色 → 权限码并集的进程内缓存值。
 *
 * @param version 生成快照时读到的全局授权版本；与当前版本不一致即视为失效
 * @param root    是否持有超管角色（不受任何权限检查）
 * @param codes   有效权限码集合（多角色并集、过滤停用角色与停用节点）
 */
public record AuthzSnapshot(long version, boolean root, Set<String> codes) {

    /** 防御性空快照：重算时用户已不存在或已禁用 → 视同无权限 */
    public static AuthzSnapshot denied(long version) {
        return new AuthzSnapshot(version, false, Set.of());
    }
}
