package cc.naihao.simplenotes;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.naihao.simplenotes.config.AuthProperties;
import cc.naihao.simplenotes.security.AuthzCache;
import cc.naihao.simplenotes.security.AuthzQuery;
import cc.naihao.simplenotes.security.AuthzSnapshot;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 授权快照缓存语义测试（docs/auth-rbac.md §5.3）：版本一致命中原值；bump 后重算。
 */
class AuthzCacheTest {

    @Test
    @DisplayName("版本一致 → 命中缓存不查库；bump 后下一次读取重算")
    void bumpForcesRecompute() {
        AuthzQuery query = mock(AuthzQuery.class);
        when(query.compute(eq("u1"), anyLong()))
                .thenAnswer(invocation -> new AuthzSnapshot(invocation.getArgument(1), false, Set.of("x")));

        AuthzCache cache = new AuthzCache(new AuthProperties(), query);

        cache.get("u1");
        cache.get("u1");
        verify(query, times(1)).compute(eq("u1"), anyLong());

        cache.bump();
        cache.get("u1");
        verify(query, times(2)).compute(eq("u1"), anyLong());

        cache.get("u1");
        verify(query, times(2)).compute(eq("u1"), anyLong());
    }
}
