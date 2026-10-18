package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.SysUserSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话仓储：撤销通道（每请求必查、不缓存）。
 *
 * <p>撤销 / 滑动续期走实体态（加载 → 改状态 → 提交时脏检查落库），不写批量 DML。
 */
public interface SysUserSessionRepository extends JpaRepository<SysUserSession, String> {

    Optional<SysUserSession> findByIdAndRevokedFalse(String id);

    List<SysUserSession> findByUserIdAndRevokedFalse(String userId);
}
