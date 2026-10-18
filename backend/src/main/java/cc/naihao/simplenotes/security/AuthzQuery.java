package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.domain.QSysMenu;
import cc.naihao.simplenotes.domain.QSysRolePermission;
import cc.naihao.simplenotes.domain.SysRole;
import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.domain.SysUserRole;
import cc.naihao.simplenotes.repository.SysRoleRepository;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.repository.SysUserRoleRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 授权计算（无缓存逻辑）：用户 → 角色（启用、未删）→ 权限节点（启用）并集。
 *
 * <p>规则（docs/auth-rbac.md §5.3）：
 *
 * <ul>
 *   <li>用户不存在 / 已禁用 → 视同无权限（防御性）；</li>
 *   <li>停用角色、停用节点均不生效；</li>
 *   <li>超管判定 = 持有启用的 SUPER_ADMIN 角色（授权短路依据）。</li>
 * </ul>
 *
 * <p>跨表读取（角色 → 权限码）按「查询约定」用 QueryDSL 表达。
 */
@Component
@RequiredArgsConstructor
public class AuthzQuery {

    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final JPAQueryFactory queryFactory;

    public AuthzSnapshot compute(String userId, long version) {
        SysUser user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != 1) {
            return AuthzSnapshot.denied(version);
        }

        List<String> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return new AuthzSnapshot(version, false, Set.of());
        }

        List<SysRole> enabledRoles = roleRepository.findAllById(roleIds).stream()
                .filter(role -> role.getStatus() == 1)
                .toList();
        if (enabledRoles.isEmpty()) {
            return new AuthzSnapshot(version, false, Set.of());
        }

        boolean root = enabledRoles.stream().anyMatch(role -> Roles.SUPER_ADMIN.equals(role.getCode()));
        Set<String> codes = findEnabledMenuCodesByRoleIds(enabledRoles.stream().map(SysRole::getId).toList());
        return new AuthzSnapshot(version, root, codes);
    }

    /** 有效权限码集合：多角色并集、过滤停用节点（docs/auth-rbac.md §5.3 快照内容） */
    private Set<String> findEnabledMenuCodesByRoleIds(List<String> roleIds) {
        QSysRolePermission permission = QSysRolePermission.sysRolePermission;
        QSysMenu menu = QSysMenu.sysMenu;
        List<String> codes = queryFactory.select(menu.code).distinct()
                .from(permission, menu)
                .where(permission.roleId.in(roleIds),
                        menu.id.eq(permission.menuId),
                        menu.status.eq((short) 1))
                .fetch();
        return Set.copyOf(codes);
    }
}
