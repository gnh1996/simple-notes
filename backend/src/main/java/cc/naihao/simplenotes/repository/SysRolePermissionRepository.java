package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SysRolePermission;
import cc.naihao.simplenotes.domain.SysRolePermissionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 角色授权仓储（物理删除例外表）；保存 = 集合全量替换。
 *
 * <p>有效权限码集合（多角色并集、过滤停用节点）为跨表查询，按约定用 QueryDSL 表达（见 {@code AuthzQuery}）。
 */
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, SysRolePermissionId> {

    List<SysRolePermission> findByRoleIdIn(Collection<String> roleIds);
}
