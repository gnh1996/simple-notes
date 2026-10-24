package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import cc.naihao.simplenotes.config.AuthProperties;
import cc.naihao.simplenotes.config.SiteProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 预览 token：签名、版本绑定、篡改与过期（docs/blog-runtime-theme.md §7.2）。 */
class PreviewTokenServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private PreviewTokenService service(Duration ttl) {
        AuthProperties auth = new AuthProperties();
        auth.setJwtSecret(SECRET);
        SiteProperties site = new SiteProperties();
        site.setPreviewTtl(ttl);
        return new PreviewTokenService(auth, site);
    }

    @Test
    @DisplayName("签发的 token 可通过校验，且与版本 id 绑定")
    void issueAndVerify() {
        PreviewTokenService service = service(Duration.ofMinutes(30));
        var issued = service.issue("version-1");

        assertThat(service.verify("version-1", issued.token())).isTrue();
        assertThat(service.verify("version-2", issued.token())).isFalse();
        assertThat(issued.expiresAt()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    @DisplayName("篡改签名 / 过期 / 格式错误均拒绝")
    void rejectInvalidTokens() {
        PreviewTokenService service = service(Duration.ofMinutes(30));
        var issued = service.issue("version-1");

        assertThat(service.verify("version-1", issued.token() + "x")).isFalse();
        assertThat(service.verify("version-1", "v1.bad.signature")).isFalse();
        assertThat(service.verify("version-1", null)).isFalse();
        assertThat(service.verify(null, issued.token())).isFalse();

        PreviewTokenService expired = service(Duration.ofSeconds(-1));
        assertThat(expired.verify("version-1", expired.issue("version-1").token())).isFalse();
    }
}
