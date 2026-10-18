package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.Perms;
import cc.naihao.simplenotes.service.UserService;
import cc.naihao.simplenotes.web.dto.AssignRolesRequest;
import cc.naihao.simplenotes.web.dto.CreateUserRequest;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.ResetPasswordRequest;
import cc.naihao.simplenotes.web.dto.UpdateUserRequest;
import cc.naihao.simplenotes.web.dto.UserResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（docs/auth-rbac.md §8）。码与 {@link Perms} 常量一致（启动对账校验注解引用）。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:user')")
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return userService.list(page, size, keyword);
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:user:create')")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:user:update')")
    public UserResponse update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("@authz.can('admin:user:assign-role')")
    public UserResponse assignRoles(@PathVariable String id, @Valid @RequestBody AssignRolesRequest request) {
        return userService.assignRoles(id, request);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:user:reset-password')")
    public void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:user:delete')")
    public void delete(@PathVariable String id) {
        userService.delete(id);
    }
}
