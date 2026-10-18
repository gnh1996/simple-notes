package cc.naihao.simplenotes.domain;

/**
 * 权限目录节点类型（docs/auth-rbac.md §3.1）。
 *
 * <ul>
 *   <li>{@link #ENTRY}：端根节点 = 入口权限；</li>
 *   <li>{@link #MENU}：菜单节点（有对应页面 = 页面可见 + 读接口；无页面 = 目录分组）；</li>
 *   <li>{@link #ACTION}：操作节点 = 按钮显隐 + 对应写接口。</li>
 * </ul>
 */
public enum MenuType {
    ENTRY,
    MENU,
    ACTION
}
