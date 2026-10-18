package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.Perms;
import cc.naihao.simplenotes.service.RoleService;
import cc.naihao.simplenotes.web.dto.CreateRoleRequest;
import cc.naihao.simplenotes.web.dto.GrantPermissionsRequest;
import cc.naihao.simplenotes.web.dto.RoleBrief;
import cc.naihao.simplenotes.web.dto.RoleResponse;
import cc.naihao.simplenotes.web.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口（docs/auth-rbac.md §8）。码与 {@link Perms} 常量一致（启动对账校验注解引用）。
 */
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:role')")
    public List<RoleResponse> list() {
        return roleService.list();
    }

    /** 可分配角色（排除内置角色，供用户管理页「分配角色」弹窗） */
    @GetMapping("/assignable")
    @PreAuthorize("@authz.can('admin:user:assign-role')")
    public List<RoleBrief> assignable() {
        return roleService.assignable();
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:role:create')")
    public RoleResponse create(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:role:update')")
    public RoleResponse update(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.update(id, request);
    }

    /** 修改角色授权（全量替换权限节点集合；与普通编辑分开，独立权限码） */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("@authz.can('admin:role:grant')")
    public RoleResponse grant(@PathVariable String id, @Valid @RequestBody GrantPermissionsRequest request) {
        return roleService.grant(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:role:delete')")
    public void delete(@PathVariable String id) {
        roleService.delete(id);
    }
}
