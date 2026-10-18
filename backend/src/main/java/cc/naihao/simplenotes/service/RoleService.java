package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.QSysUser;
import cc.naihao.simplenotes.domain.QSysUserRole;
import cc.naihao.simplenotes.domain.SysRole;
import cc.naihao.simplenotes.domain.SysRolePermission;
import cc.naihao.simplenotes.repository.SysMenuRepository;
import cc.naihao.simplenotes.repository.SysRolePermissionRepository;
import cc.naihao.simplenotes.repository.SysRoleRepository;
import cc.naihao.simplenotes.security.AuthzCache;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.CreateRoleRequest;
import cc.naihao.simplenotes.web.dto.GrantPermissionsRequest;
import cc.naihao.simplenotes.web.dto.RoleBrief;
import cc.naihao.simplenotes.web.dto.RoleResponse;
import cc.naihao.simplenotes.web.dto.UpdateRoleRequest;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理（admin 端，docs/auth-rbac.md §8）。
 *
 * <p>内置角色（builtin=1，预置 SUPER_ADMIN）不可编辑 / 删除 / 分配；角色删除要求无未删除用户占用；
 * 授权 = 集合全量替换（删除 + 重建）；全部写操作在事务提交后 bump 授权快照。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysMenuRepository menuRepository;
    private final AuthzCache authzCache;
    private final JPAQueryFactory queryFactory;

    /** 角色列表：按 sort 升序；含已授权节点 id 供角色编辑器回显 */
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        List<SysRole> roles = roleRepository.findAllByOrderBySortAscCreateTimeDesc();
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> permissionIdsByRole = rolePermissionRepository
                .findByRoleIdIn(roles.stream().map(SysRole::getId).toList()).stream()
                .collect(Collectors.groupingBy(SysRolePermission::getRoleId,
                        Collectors.mapping(SysRolePermission::getMenuId, Collectors.toList())));
        return roles.stream()
                .map(role -> toResponse(role, permissionIdsByRole.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    /** 可分配角色（供用户管理页「分配角色」弹窗）：排除内置角色，仅启用 */
    @Transactional(readOnly = true)
    public List<RoleBrief> assignable() {
        return roleRepository.findByStatusAndBuiltinOrderBySortAscCreateTimeDesc((short) 1, (short) 0).stream()
                .map(RoleBrief::from)
                .toList();
    }

    @Transactional
    public RoleResponse create(CreateRoleRequest request) {
        String code = request.code().trim();
        if (roleRepository.existsByCode(code)) {
            throw ApiException.conflict("角色码已存在");
        }
        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(request.name().trim());
        role.setDescription(trimToNull(request.description()));
        role.setSort(request.sort() == null ? 0 : request.sort());
        role.setCreateBy(SecurityUtils.currentUserId());
        roleRepository.save(role);
        authzCache.bumpAfterCommit();
        return toResponse(role, List.of());
    }

    /** 编辑角色：名称 / 描述 / 排序 / 状态（停用 = 授权不生效，下次请求即生效） */
    @Transactional
    public RoleResponse update(String id, UpdateRoleRequest request) {
        SysRole role = requireRole(id);
        requireNotBuiltin(role);
        role.setName(request.name().trim());
        role.setDescription(trimToNull(request.description()));
        role.setSort(request.sort());
        role.setStatus(request.status().shortValue());
        role.setUpdateBy(SecurityUtils.currentUserId());
        roleRepository.save(role);
        authzCache.bumpAfterCommit();
        return toResponse(role, permissionIds(role.getId()));
    }

    /** 修改授权：全量替换权限节点集合（停用节点可保存，运行时视同未授予） */
    @Transactional
    public RoleResponse grant(String id, GrantPermissionsRequest request) {
        SysRole role = requireRole(id);
        requireNotBuiltin(role);
        List<String> menuIds = request.menuIds().stream().distinct().toList();
        if (!menuIds.isEmpty() && menuRepository.findAllById(menuIds).size() != menuIds.size()) {
            throw ApiException.badRequest("存在无效的权限节点 id");
        }

        replacePermissions(role.getId(), menuIds, SecurityUtils.currentUserId());
        authzCache.bumpAfterCommit();
        return toResponse(role, menuIds);
    }

    /** 删除角色：内置角色拒绝；有未删除用户占用时拒绝（409）；同时清理授权行 */
    @Transactional
    public void delete(String id) {
        SysRole role = requireRole(id);
        requireNotBuiltin(role);
        if (countActiveUsersByRoleId(role.getId()) > 0) {
            throw ApiException.conflict("仍有用户使用该角色，无法删除");
        }
        role.markDeleted(SecurityUtils.currentUserId());
        roleRepository.save(role);
        rolePermissionRepository.deleteAll(rolePermissionRepository.findByRoleIdIn(List.of(role.getId())));
        authzCache.bumpAfterCommit();
    }

    /**
     * 集合全量替换（docs/auth-rbac.md §3.5：事务内 delete + insert）。
     *
     * <p>实体态删除 + 显式 flush：Hibernate 的 flush 顺序为先 insert 后 delete，
     * "删除旧集合 + 插入同 PK 新集合"若落在同一次 flush 内会撞主键，必须先删干净再插。
     */
    private void replacePermissions(String roleId, List<String> menuIds, String operator) {
        rolePermissionRepository.deleteAll(rolePermissionRepository.findByRoleIdIn(List.of(roleId)));
        rolePermissionRepository.flush();
        rolePermissionRepository.saveAll(menuIds.stream()
                .map(menuId -> new SysRolePermission(roleId, menuId, operator))
                .toList());
    }

    /** 角色占用判断：仅统计未删除用户（docs/auth-rbac.md §8 角色删除口径） */
    private long countActiveUsersByRoleId(String roleId) {
        QSysUserRole binding = QSysUserRole.sysUserRole;
        QSysUser user = QSysUser.sysUser;
        Long count = queryFactory.select(binding.count())
                .from(binding)
                .where(binding.roleId.eq(roleId),
                        JPAExpressions.selectOne().from(user).where(user.id.eq(binding.userId)).exists())
                .fetchOne();
        return count == null ? 0L : count;
    }

    private void requireNotBuiltin(SysRole role) {
        if (role.getBuiltin() == 1) {
            throw ApiException.permissionDenied("内置角色不可编辑 / 删除 / 分配");
        }
    }

    private SysRole requireRole(String id) {
        return roleRepository.findById(id).orElseThrow(() -> ApiException.notFound("角色不存在"));
    }

    private List<String> permissionIds(String roleId) {
        return rolePermissionRepository.findByRoleIdIn(List.of(roleId)).stream()
                .map(SysRolePermission::getMenuId)
                .toList();
    }

    private RoleResponse toResponse(SysRole role, List<String> permissionIds) {
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.getBuiltin() == 1, role.getSort(), role.getStatus(), permissionIds, role.getCreateTime());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
