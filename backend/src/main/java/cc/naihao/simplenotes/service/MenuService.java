package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.MenuType;
import cc.naihao.simplenotes.domain.SysMenu;
import cc.naihao.simplenotes.repository.SysMenuRepository;
import cc.naihao.simplenotes.security.AuthzCache;
import cc.naihao.simplenotes.security.AuthzSnapshot;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.MenuCatalogNode;
import cc.naihao.simplenotes.web.dto.MenuNode;
import cc.naihao.simplenotes.web.dto.UpdateMenuRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限目录（sys_menu）服务（docs/auth-rbac.md §3.4 / §6.2）。
 *
 * <ul>
 *   <li>{@link #fullCatalog()}：完整目录树（含停用节点与 ACTION；按端以 ENTRY 为根），
 *       角色编辑器与菜单管理共用；</li>
 *   <li>{@link #visibleMenuTree(AuthzSnapshot)}：me 的可见菜单树（仅 MENU；纳入规则
 *       "自身有授权或有可见后代"；超管返回全量）；</li>
 *   <li>{@link #update(String, UpdateMenuRequest)}：改名 / 调序 / 启停（结构性字段与增删不提供）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final SysMenuRepository menuRepository;
    private final AuthzCache authzCache;

    /** 完整目录树：结构字段只读，payload 含 type / client 供管理端按端分 Tab 渲染 */
    @Transactional(readOnly = true)
    public List<MenuCatalogNode> fullCatalog() {
        List<SysMenu> all = menuRepository.findAllByOrderBySortAscCreateTimeAsc();
        Map<String, SysMenu> byId = all.stream().collect(Collectors.toMap(SysMenu::getId, Function.identity()));
        Map<String, List<SysMenu>> childrenByParent = new HashMap<>();
        List<SysMenu> roots = new ArrayList<>();
        for (SysMenu node : all) {
            SysMenu parent = node.getParentId() == null ? null : byId.get(node.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                childrenByParent.computeIfAbsent(parent.getId(), _ -> new ArrayList<>()).add(node);
            }
        }
        return roots.stream().map(node -> toCatalogNode(node, childrenByParent)).toList();
    }

    /**
     * me 的可见菜单树（仅 ADMIN 端调用）：
     *
     * <ul>
     *   <li>超管：返回全量启用 MENU（不做过滤）；</li>
     *   <li>普通用户：节点可见 = 自身有授权或有可见后代（ACTION 授权使其所在页面可见，"勾选即显示"）；</li>
     *   <li>ACTION 不出现在菜单载荷中；停用节点不参与。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<MenuNode> visibleMenuTree(AuthzSnapshot snapshot) {
        List<SysMenu> clientNodes = menuRepository
                .findByClientAndStatusOrderBySortAscCreateTimeAsc(ClientType.ADMIN, (short) 1);
        Set<String> visibleIds = computeVisibleIds(clientNodes, snapshot);

        Map<String, SysMenu> visibleById = clientNodes.stream()
                .filter(node -> node.getType() == MenuType.MENU && visibleIds.contains(node.getId()))
                .collect(Collectors.toMap(SysMenu::getId, Function.identity()));
        List<SysMenu> tops = new ArrayList<>();
        Map<String, List<SysMenu>> childrenByParent = new HashMap<>();
        for (SysMenu node : visibleById.values()) {
            SysMenu parent = node.getParentId() == null ? null : visibleById.get(node.getParentId());
            if (parent != null) {
                childrenByParent.computeIfAbsent(parent.getId(), _ -> new ArrayList<>()).add(node);
            } else {
                tops.add(node);
            }
        }
        // 查询已按 sort / createTime 排序，分组与顶层均保持稳定顺序
        tops.sort(this::compareOrder);
        childrenByParent.values().forEach(list -> list.sort(this::compareOrder));
        return tops.stream().map(node -> toMenuNode(node, childrenByParent)).toList();
    }

    /** 改名 / 调序 / 启停（界面可改字段；启停即时影响授权与菜单可见性） */
    @Transactional
    public MenuCatalogNode update(String id, UpdateMenuRequest request) {
        SysMenu node = menuRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("菜单节点不存在"));
        node.setName(request.name().trim());
        node.setSort(request.sort());
        node.setStatus(request.status().shortValue());
        node.setUpdateBy(SecurityUtils.currentUserId());
        menuRepository.save(node);
        authzCache.bumpAfterCommit();
        return new MenuCatalogNode(node.getId(), node.getParentId(), node.getClient(), node.getType(),
                node.getCode(), node.getName(), node.getSort(), node.getStatus(), List.of());
    }

    /**
     * 可见节点集合：自身授权 ∪ 其在端内树上的 MENU 祖链；超管 = 全量 MENU。
     *
     * <p>授权 MENU 使自身与祖先目录可见；授权 ACTION 使所在页面（及其祖先）可见——
     * 即"勾选即显示"（docs/auth-rbac.md §3.1）；ENTRY 授权不产生菜单。
     */
    private Set<String> computeVisibleIds(List<SysMenu> clientNodes, AuthzSnapshot snapshot) {
        Map<String, SysMenu> byId = clientNodes.stream()
                .collect(Collectors.toMap(SysMenu::getId, Function.identity()));
        Set<String> visible = new HashSet<>();
        if (snapshot.root()) {
            clientNodes.stream()
                    .filter(node -> node.getType() == MenuType.MENU)
                    .forEach(node -> visible.add(node.getId()));
            return visible;
        }
        for (SysMenu node : clientNodes) {
            if (node.getType() == MenuType.ENTRY || !snapshot.codes().contains(node.getCode())) {
                continue;
            }
            SysMenu cursor = node;
            while (cursor != null) {
                if (cursor.getType() == MenuType.MENU) {
                    visible.add(cursor.getId());
                }
                cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
            }
        }
        return visible;
    }

    private MenuCatalogNode toCatalogNode(SysMenu node, Map<String, List<SysMenu>> childrenByParent) {
        List<MenuCatalogNode> children = childrenByParent.getOrDefault(node.getId(), List.of()).stream()
                .map(child -> toCatalogNode(child, childrenByParent))
                .toList();
        return new MenuCatalogNode(node.getId(), node.getParentId(), node.getClient(), node.getType(),
                node.getCode(), node.getName(), node.getSort(), node.getStatus(), children);
    }

    private MenuNode toMenuNode(SysMenu node, Map<String, List<SysMenu>> childrenByParent) {
        List<MenuNode> children = childrenByParent.getOrDefault(node.getId(), List.of()).stream()
                .map(child -> toMenuNode(child, childrenByParent))
                .toList();
        return new MenuNode(node.getId(), node.getCode(), node.getName(), node.getSort(), children);
    }

    private int compareOrder(SysMenu left, SysMenu right) {
        int bySort = Integer.compare(left.getSort(), right.getSort());
        if (bySort != 0) {
            return bySort;
        }
        return left.getCreateTime().compareTo(right.getCreateTime());
    }
}
