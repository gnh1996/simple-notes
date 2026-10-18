package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.Perms;
import cc.naihao.simplenotes.service.MenuService;
import cc.naihao.simplenotes.web.dto.MenuCatalogNode;
import cc.naihao.simplenotes.web.dto.UpdateMenuRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单管理接口（docs/auth-rbac.md §8）：完整目录树读取 + 改名 / 调序 / 启停。
 * 码与 {@link Perms} 常量一致（启动对账校验注解引用）。
 */
@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuService menuService;

    /** 完整菜单树：角色编辑器与菜单管理共用（{@code admin:role} 或 {@code admin:menu} 任一） */
    @GetMapping
    @PreAuthorize("@authz.canAny('admin:role', 'admin:menu')")
    public List<MenuCatalogNode> list() {
        return menuService.fullCatalog();
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:menu:update')")
    public MenuCatalogNode update(@PathVariable String id, @Valid @RequestBody UpdateMenuRequest request) {
        return menuService.update(id, request);
    }
}
