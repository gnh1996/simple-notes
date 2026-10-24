package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.config.AuthProperties;
import cc.naihao.simplenotes.config.SiteProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 主题预览短期签名（docs/blog-runtime-theme.md §7.2）。
 *
 * <p>token 格式：{@code v1.<expEpochSecond>.<base64url(hmac)>}；签名 = HMAC-SHA256(versionId + "." + exp)。
 * 只用于“看草稿主题”，不携带任何用户身份。
 */
@Service
@RequiredArgsConstructor
public class PreviewTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthProperties authProperties;
    private final SiteProperties siteProperties;

    public IssuedToken issue(String versionId) {
        long expiresAt = Instant.now().plus(siteProperties.getPreviewTtl()).getEpochSecond();
        String payload = versionId + "." + expiresAt;
        return new IssuedToken("v1." + expiresAt + "." + sign(payload), expiresAt);
    }

    public boolean verify(String versionId, String token) {
        if (token == null || versionId == null) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        String expected = sign(versionId + "." + expiresAt);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("签发预览 token 失败", e);
        }
    }

    public record IssuedToken(String token, long expiresAt) {
    }
}
