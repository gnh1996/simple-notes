package cc.naihao.simplenotes.security;

/**
 * 内置角色码（由初始迁移预置，见 docs/auth-rbac.md §4）。
 */
public final class Roles {

    /** 超管角色：授权短路唯一依据 = 用户是否持有该角色；不可编辑 / 删除 / 分配给其他用户 */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    private Roles() {
    }
}
