package cc.naihao.simplenotes.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.naihao.simplenotes.domain.ClientType;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 认证配置校验（docs/auth-rbac.md §9）：JWT 密钥缺失 / 占位符未解析 / 过短均启动失败。
 */
class AuthPropertiesTest {

    @Test
    @DisplayName("密钥缺失、未解析占位符、不足 32 字节 → 启动校验失败")
    void invalidJwtSecretFailsFast() {
        AuthProperties blank = new AuthProperties();
        assertThatThrownBy(blank::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 JWT 密钥");

        AuthProperties unresolved = new AuthProperties();
        unresolved.setJwtSecret("${JWT_SECRET}");
        assertThatThrownBy(unresolved::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 JWT 密钥");

        AuthProperties tooShort = new AuthProperties();
        tooShort.setJwtSecret("short-secret");
        assertThatThrownBy(tooShort::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥过短");
    }

    @Test
    @DisplayName("会话时限按端取值（Web 7 天 / 桌面 30 天空闲，绝对上限 30 / 180 天）")
    void sessionPolicyByClient() {
        AuthProperties properties = valid();
        assertThat(properties.idleTimeout(ClientType.ADMIN)).isEqualTo(Duration.ofDays(7));
        assertThat(properties.idleTimeout(ClientType.BLOG)).isEqualTo(Duration.ofDays(7));
        assertThat(properties.idleTimeout(ClientType.DESKTOP)).isEqualTo(Duration.ofDays(30));
        assertThat(properties.absoluteTimeout(ClientType.ADMIN)).isEqualTo(Duration.ofDays(30));
        assertThat(properties.absoluteTimeout(ClientType.DESKTOP)).isEqualTo(Duration.ofDays(180));
    }

    private AuthProperties valid() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("0123456789abcdef0123456789abcdef");
        properties.validate();
        return properties;
    }
}
