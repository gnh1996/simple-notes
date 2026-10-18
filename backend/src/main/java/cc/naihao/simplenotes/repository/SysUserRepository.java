package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SysUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户仓储。实体带 {@code @SQLRestriction("delete_flag = 0")}：查询默认排除已删除用户。
 */
public interface SysUserRepository extends JpaRepository<SysUser, String> {

    Optional<SysUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
