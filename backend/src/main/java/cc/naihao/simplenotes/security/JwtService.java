package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * JWT 签发与校验（HS256，docs/auth-rbac.md §5.1）。
 *
 * <p>claims 最小集：{@code sub}（用户）/ {@code jti}（会话）/ {@code iat} / {@code exp}；
 * 权限不嵌入 JWT（角色 / 权限变更不失效 token）。
 * 校验 = 验签 + exp（本地快速失败）；会话存在性另由 {@link SessionService} 每请求必查。
 * JWT 明文不落库、不落日志。
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtService(AuthProperties properties) {
        SecretKey key = new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * 签发 JWT：exp 与会话行的 expire_time 一致（绝对上限）。
     */
    public String issue(String userId, String sessionId, Instant issuedAt, Instant expiresAt) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .id(sessionId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 验签 + exp 校验（本地快速失败）；畸形 / 过期 / 签名不符均抛 {@link JwtException}。
     */
    public JwtPayload verify(String token) {
        Jwt jwt = decoder.decode(token);
        if (jwt.getId() == null || jwt.getSubject() == null) {
            throw new BadJwtException("JWT 缺少 jti / sub");
        }
        return new JwtPayload(jwt.getSubject(), jwt.getId());
    }

    /** JWT 中提取的最小集信息 */
    public record JwtPayload(String userId, String sessionId) {
    }
}
