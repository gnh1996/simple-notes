package cc.naihao.simplenotes.web.dto;

import java.util.List;

/**
 * 可见菜单节点（me.menus）：仅 MENU 类型；ACTON 不出现在菜单载荷中（docs/auth-rbac.md §6.2）。
 *
 * <p>{@code code} 供前端页面注册表映射路径与组件；名称 / 顺序 / 层级均以 sys_menu 为准。
 */
public record MenuNode(String id, String code, String name, int sort, List<MenuNode> children) {
}
