package cc.naihao.simplenotes.config;

import cc.naihao.simplenotes.domain.ClientType;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证与授权配置（{@code simple-notes.auth.*}）。
 *
 * <p>JWT 密钥无默认值：缺失或不足 32 字节时启动失败（fail-fast，docs/auth-rbac.md §9）。
 * 会话时限与缓存参数有合理默认值（docs/auth-rbac.md §5.3 表格），一般无需配置，测试可覆盖。
 */
@ConfigurationProperties(prefix = "simple-notes.auth")
@Getter
@Setter
public class AuthProperties {

    /** JWT 签名密钥（HS256）：32+ 字节随机值（本地 application-local.yml / 部署环境变量 JWT_SECRET） */
    private String jwtSecret;

    // ---- 会话时限（docs/auth-rbac.md §5.3 表格） ----

    /** Web（ADMIN / BLOG）空闲超时 */
    private Duration webIdleTimeout = Duration.ofDays(7);
    /** Web（ADMIN / BLOG）绝对上限（= JWT exp） */
    private Duration webAbsoluteTimeout = Duration.ofDays(30);
    /** 桌面端空闲超时 */
    private Duration desktopIdleTimeout = Duration.ofDays(30);
    /** 桌面端绝对上限（= JWT exp） */
    private Duration desktopAbsoluteTimeout = Duration.ofDays(180);
    /** 会话滑动续期的最小回写间隔（避免每请求写库） */
    private Duration sessionTouchInterval = Duration.ofMinutes(5);

    // ---- 授权快照 ----

    /** 快照 TTL：bump 失效是即时路径，TTL 仅为兜底保险丝 */
    private Duration snapshotTtl = Duration.ofMinutes(10);

    // ---- 登录限流（内存级实现，重启即清） ----

    /** 连续失败次数阈值（按"用户名 + IP"双维度计数） */
    private int loginFailureThreshold = 5;
    /** 触发阈值后的锁定时长 */
    private Duration loginLockDuration = Duration.ofSeconds(60);
    /** 失败计数的保留时长（窗口） */
    private Duration loginFailureWindow = Duration.ofHours(1);

    @PostConstruct
    void validate() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.startsWith("${")) {
            throw new IllegalStateException(
                    "缺少 JWT 密钥：请配置 simple-notes.auth.jwt-secret（本地 application-local.yml）或环境变量 JWT_SECRET");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 密钥过短：需为 32+ 字节随机值（HS256 要求），当前 "
                    + jwtSecret.getBytes(StandardCharsets.UTF_8).length + " 字节");
        }
    }

    public Duration idleTimeout(ClientType client) {
        return client == ClientType.DESKTOP ? desktopIdleTimeout : webIdleTimeout;
    }

    public Duration absoluteTimeout(ClientType client) {
        return client == ClientType.DESKTOP ? desktopAbsoluteTimeout : webAbsoluteTimeout;
    }
}
