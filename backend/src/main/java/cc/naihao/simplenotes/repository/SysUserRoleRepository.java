package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SysUserRole;
import cc.naihao.simplenotes.domain.SysUserRoleId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户-角色关联仓储（物理删除例外表）。
 *
 * <p>复合主键为 {@code @IdClass} 扁平字段：派生查询按实体字段直接表达（{@code findByUserId}）。
 * 集合替换的删除走实体态（加载 → {@code deleteAll} → flush → 新增），不写手写 JPQL。
 */
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, SysUserRoleId> {

    List<SysUserRole> findByUserId(String userId);

    List<SysUserRole> findByUserIdIn(Collection<String> userIds);
}
