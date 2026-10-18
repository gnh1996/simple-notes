package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.common.ErrorCodes;
import cc.naihao.simplenotes.config.AuthProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 登录限流（docs/auth-rbac.md §5.2）：按"用户名 + IP"双维度计数，连续失败达阈值 → 锁定 60 秒。
 *
 * <p>内存级实现（Caffeine）：重启即清、单实例前提（与授权快照缓存同一前提）。
 */
@Component
public class LoginRateLimiter {

    private final AuthProperties properties;
    private final Cache<String, Integer> failures;
    private final Cache<String, Instant> locks;

    public LoginRateLimiter(AuthProperties properties) {
        this.properties = properties;
        this.failures = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.getLoginFailureWindow())
                .build();
        this.locks = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.getLoginLockDuration())
                .build();
    }

    /** 锁定中 → 429（在验密之前判定，避免对锁定账号做无谓的 bcrypt 计算） */
    public void checkAllowed(String username, String clientIp) {
        if (locks.getIfPresent(key(username, clientIp)) != null) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.LOGIN_RATE_LIMITED,
                    "登录尝试过于频繁，请稍后再试");
        }
    }

    public void recordFailure(String username, String clientIp) {
        String key = key(username, clientIp);
        Integer count = failures.asMap().merge(key, 1, Integer::sum);
        if (count >= properties.getLoginFailureThreshold()) {
            locks.put(key, Instant.now().plus(properties.getLoginLockDuration()));
            failures.invalidate(key);
        }
    }

    public void recordSuccess(String username, String clientIp) {
        String key = key(username, clientIp);
        failures.invalidate(key);
        locks.invalidate(key);
    }

    private String key(String username, String clientIp) {
        return username + "|" + clientIp;
    }
}
