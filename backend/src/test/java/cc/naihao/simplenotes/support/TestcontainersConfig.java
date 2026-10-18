package cc.naihao.simplenotes.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试用 PostgreSQL 容器：连接信息经 {@code @ServiceConnection} 注入，
 * 覆盖 application.yml / application-local.yml 中的任意数据源配置——
 * 测试只连容器库，绝不触碰本地 / 远程开发库。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("simple_notes")
                .withUsername("simple_notes")
                .withPassword("simple_notes");
    }
}
