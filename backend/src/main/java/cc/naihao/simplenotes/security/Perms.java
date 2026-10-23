package cc.naihao.simplenotes.security;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 权限码常量类（docs/auth-rbac.md §3.4）：代码侧引用的全量权限码清单。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>接口注解（{@code @PreAuthorize("@authz.can('...')")}）与前端注册表 / 库中授权数据共同引用同一套码；</li>
 *   <li>启动对账（{@link cc.naihao.simplenotes.startup.MenuCatalogValidator}）扫描本类全部常量，
 *       逐一核对 {@code sys_menu} 存在对应节点，并校验注解引用的码均在此声明（缺失则启动失败）。</li>
 * </ul>
 *
 * <p>新增权限的标准动作 = 本类加常量 + 一条 Flyway INSERT 迁移（对账兜底）。
 * 码一经发布不可更改；废弃 = 停用节点（{@code status=0}）。
 *
 * <p>注：注解里的码写成字符串字面量（SpEL 里引用 Java 常量需 {@code T(全限定名).常量}，可读性差），
 * 因此启动对账额外扫描全部 {@code @PreAuthorize}，保证注解引用的码一定在 Perms 中声明——
 * 手写偏差同样 fail-fast。
 */
public final class Perms {

    // ---- 端入口（ENTRY） ----

    public static final String ENTRY_ADMIN = "entry:admin";
    public static final String ENTRY_BLOG = "entry:blog";
    public static final String ENTRY_DESKTOP = "entry:desktop";

    // ---- admin：用户管理 ----

    public static final String ADMIN_USER = "admin:user";
    public static final String ADMIN_USER_CREATE = "admin:user:create";
    public static final String ADMIN_USER_UPDATE = "admin:user:update";
    public static final String ADMIN_USER_ASSIGN_ROLE = "admin:user:assign-role";
    public static final String ADMIN_USER_RESET_PASSWORD = "admin:user:reset-password";
    public static final String ADMIN_USER_DELETE = "admin:user:delete";

    // ---- admin：角色管理 ----

    public static final String ADMIN_ROLE = "admin:role";
    public static final String ADMIN_ROLE_CREATE = "admin:role:create";
    public static final String ADMIN_ROLE_UPDATE = "admin:role:update";
    public static final String ADMIN_ROLE_GRANT = "admin:role:grant";
    public static final String ADMIN_ROLE_DELETE = "admin:role:delete";

    // ---- admin：菜单管理 ----

    public static final String ADMIN_MENU = "admin:menu";
    public static final String ADMIN_MENU_UPDATE = "admin:menu:update";

    // ---- admin：随手记 ----

    public static final String ADMIN_NOTE = "admin:note";
    public static final String ADMIN_NOTE_CREATE = "admin:note:create";
    public static final String ADMIN_NOTE_UPDATE = "admin:note:update";
    public static final String ADMIN_NOTE_DELETE = "admin:note:delete";

    // ---- admin：博客管理 ----

    public static final String ADMIN_POST = "admin:post";
    public static final String ADMIN_POST_CREATE = "admin:post:create";
    public static final String ADMIN_POST_UPDATE = "admin:post:update";
    public static final String ADMIN_POST_PUBLISH = "admin:post:publish";
    public static final String ADMIN_POST_UNPUBLISH = "admin:post:unpublish";
    public static final String ADMIN_POST_DELETE = "admin:post:delete";

    // ---- admin：媒体库 ----

    public static final String ADMIN_MEDIA = "admin:media";
    public static final String ADMIN_MEDIA_UPLOAD = "admin:media:upload";
    public static final String ADMIN_MEDIA_DELETE = "admin:media:delete";

    private static final Set<String> ALL = collectAll();

    private Perms() {
    }

    /** 全部权限码（不可变集合，启动对账 / 有效性判断用） */
    public static Set<String> all() {
        return ALL;
    }

    public static boolean isDeclared(String code) {
        return ALL.contains(code);
    }

    private static Set<String> collectAll() {
        Set<String> codes = new TreeSet<>();
        for (Field field : Perms.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                codes.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("扫描权限码常量失败：" + field.getName(), e);
            }
        }
        return Collections.unmodifiableSet(codes);
    }
}
