package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SysRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 角色仓储。实体带 {@code @SQLRestriction("delete_flag = 0")}：查询默认排除已删除角色。
 */
public interface SysRoleRepository extends JpaRepository<SysRole, String> {

    /** 角色列表：按 sort 升序，同序按创建时间降序 */
    List<SysRole> findAllByOrderBySortAscCreateTimeDesc();

    /** 可分配角色：启用 + 非内置（内置角色不可分配给其他用户，docs/auth-rbac.md §4） */
    List<SysRole> findByStatusAndBuiltinOrderBySortAscCreateTimeDesc(short status, short builtin);

    boolean existsByCode(String code);
}
