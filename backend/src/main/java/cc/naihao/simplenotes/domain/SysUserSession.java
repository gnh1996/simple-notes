package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 登录会话（docs/auth-rbac.md §7.5 运行数据例外：无软删与完整审计，撤销保留行以留痕）。
 *
 * <p>主键即 JWT 的 {@code jti}；"让 JWT 失效" = 撤销会话行（下一次请求即 401）。
 * 该表是撤销通道，查询不做缓存（每请求必查）。
 */
@Entity
@Table(name = "sys_user_session")
@Getter
@Setter
public class SysUserSession implements Persistable<String> {

    /** JWT 的 jti */
    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client", length = 16, nullable = false, updatable = false)
    private ClientType client;

    /** 最近活跃时间（滑动续期；间隔超过 5 分钟才回写） */
    @Column(name = "last_active_time", nullable = false)
    private Instant lastActiveTime;

    /** 绝对上限 = 签发时间 + 该端上限（与 JWT exp 一致） */
    @Column(name = "expire_time", nullable = false, updatable = false)
    private Instant expireTime;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "revoke_time")
    private Instant revokeTime;

    /** 签发时间（与 JWT iat 一致） */
    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
