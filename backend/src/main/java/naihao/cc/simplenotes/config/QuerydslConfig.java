package naihao.cc.simplenotes.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 基础设施：暴露共享的 {@link JPAQueryFactory}，用于构造类型安全查询 / 动态条件查询。
 *
 * <p>仓储层的 {@code QuerydslPredicateExecutor} 按需在各自 Repository 接口上扩展即可，无需在此配置。
 */
@Configuration
public class QuerydslConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
