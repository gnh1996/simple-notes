package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.config.AuthProperties;
import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.SysUserSession;
import cc.naihao.simplenotes.repository.SysUserSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话记录管理（docs/auth-rbac.md §5.1 / §5.3 / §5.4）：服务端撤销通道。
 *
 * <p>会话存数据库而非纯内存：部署重启不丢会话、可运维查询与留痕、未来多实例天然一致。
 * "让 JWT 失效" = 撤销会话行 —— 下次请求即 401。
 *
 * <p>实现口径：撤销 / 滑动续期均为实体态操作（加载 → 改状态 → 提交时脏检查落库），
 * 不写批量 DML；撤销路径集中在本类，不要在业务代码里散写。
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SysUserSessionRepository repository;
    private final AuthProperties properties;

    /** 签发会话行：id = jti，expire_time = 签发 + 该端绝对上限（与 JWT exp 一致） */
    @Transactional
    public SysUserSession create(String userId, ClientType client) {
        Instant now = Instant.now();
        SysUserSession session = new SysUserSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setClient(client);
        session.setLastActiveTime(now);
        session.setExpireTime(now.plus(properties.absoluteTimeout(client)));
        session.setCreateTime(now);
        return repository.save(session);
    }

    /**
     * 会话校验 + 滑动续期（每个请求）：
     *
     * <ol>
     *   <li>会话行存在且未撤销、sub 与会话 user_id 一致；</li>
     *   <li>空闲 / 绝对超时（判为超时 → 顺带撤销该会话行）；</li>
     *   <li>距上次活跃超过阈值（默认 5 分钟）才回写 last_active_time，避免每请求写库。</li>
     * </ol>
     *
     * @return 校验通过的会话；任一条件不满足返回空（调用方按 401 处理）
     */
    @Transactional
    public Optional<SysUserSession> validateAndTouch(String sessionId, String userId) {
        SysUserSession session = repository.findByIdAndRevokedFalse(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (!now.isBefore(session.getExpireTime())) {
            // 绝对上限兜底（JWT exp 已校验，此处防 JWT 与会话行时间不一致）
            revoke(session, now);
            return Optional.empty();
        }
        if (now.isAfter(session.getLastActiveTime().plus(properties.idleTimeout(session.getClient())))) {
            revoke(session, now);
            return Optional.empty();
        }
        if (now.isAfter(session.getLastActiveTime().plus(properties.getSessionTouchInterval()))) {
            session.setLastActiveTime(now);
        }
        return Optional.of(session);
    }

    /** 撤销单个会话（登出 / 入口失效） */
    @Transactional
    public void revoke(String sessionId) {
        repository.findByIdAndRevokedFalse(sessionId).ifPresent(session -> revoke(session, Instant.now()));
    }

    /** 撤销该用户全部会话（禁用 / 删除 / 重置密码 / logout-all） */
    @Transactional
    public void revokeAll(String userId) {
        Instant now = Instant.now();
        repository.findByUserIdAndRevokedFalse(userId).forEach(session -> revoke(session, now));
    }

    /** 撤销该用户除当前会话外的全部会话（本人改密码） */
    @Transactional
    public void revokeAllExcept(String userId, String keepSessionId) {
        Instant now = Instant.now();
        repository.findByUserIdAndRevokedFalse(userId).stream()
                .filter(session -> !session.getId().equals(keepSessionId))
                .forEach(session -> revoke(session, now));
    }

    /** 撤销 = 标记并记录时间（保留行以留痕）；脏检查在当前事务提交时落库 */
    private void revoke(SysUserSession session, Instant now) {
        session.setRevoked(true);
        session.setRevokeTime(now);
    }
}
