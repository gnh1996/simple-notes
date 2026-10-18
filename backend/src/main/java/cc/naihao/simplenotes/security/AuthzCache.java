package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.config.AuthProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 授权快照缓存（docs/auth-rbac.md §5.3）：用户粒度、全局版本校验 + TTL 兜底。
 *
 * <ul>
 *   <li>命中（版本一致且未过期）：纯内存查找、零库查询；</li>
 *   <li>失效：任一用户 / 角色 / 菜单写操作事务提交后 {@link #bumpAfterCommit()}（全局版本 +1），
 *       所有快照在下一次请求即重算；</li>
 *   <li>兜底：快照带 TTL（默认 10 分钟），防"漏调 bump"导致的长期陈旧；</li>
 *   <li>写入顺序：<b>先读全局版本、再查库</b>，避免与 bump 竞态写出"新版本号 + 旧内容"。</li>
 * </ul>
 *
 * <p>单实例部署前提（多实例仅剩 TTL 兜底，见 docs/auth-rbac.md §12）。
 */
@Component
public class AuthzCache {

    private final AuthzQuery authzQuery;
    private final AtomicLong version = new AtomicLong();
    private final Cache<String, AuthzSnapshot> cache;

    public AuthzCache(AuthProperties properties, AuthzQuery authzQuery) {
        this.authzQuery = authzQuery;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.getSnapshotTtl())
                .build();
    }

    public AuthzSnapshot get(String userId) {
        AuthzSnapshot cached = cache.getIfPresent(userId);
        if (cached != null && cached.version() == version.get()) {
            return cached;
        }
        // 先读版本再查库：若期间发生 bump，写入的快照版本号落后于全局版本，
        // 下一次读取即判失效并重算（不会把旧内容标记成新版本）
        long snapshotVersion = version.get();
        AuthzSnapshot fresh = authzQuery.compute(userId, snapshotVersion);
        cache.put(userId, fresh);
        return fresh;
    }

    /** 全局失效（登录预热 / 测试直接调用；写路径请用 {@link #bumpAfterCommit()}） */
    public void bump() {
        version.incrementAndGet();
    }

    /** 事务提交后 bump（无事务上下文则立即执行）：保证回滚的写操作不触发无谓重算 */
    public void bumpAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bump();
                }
            });
        } else {
            bump();
        }
    }
}
