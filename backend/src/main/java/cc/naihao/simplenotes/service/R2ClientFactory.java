package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import java.net.URI;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * R2 / S3 客户端懒加载（docs/attachment-storage.md §5.2）。
 *
 * <p>LOCAL / DATABASE 模式不需要 R2 凭据，因此这里不能在构造期连接或校验；
 * 只有真正上传、读取、presign 或启动校验命中 OSS 时才要求配置完整。
 */
@Component
@RequiredArgsConstructor
public class R2ClientFactory {

    private final StorageProperties properties;

    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public S3Client client() {
        S3Client current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = buildClient();
                }
                current = client;
            }
        }
        return current;
    }

    public S3Presigner presigner() {
        S3Presigner current = presigner;
        if (current == null) {
            synchronized (this) {
                if (presigner == null) {
                    presigner = buildPresigner();
                }
                current = presigner;
            }
        }
        return current;
    }

    public StorageProperties.R2 r2() {
        return properties.getR2();
    }

    public String bucket() {
        return properties.getR2().getBucket();
    }

    public String objectKey(String storageKey) {
        String prefix = properties.getR2().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            return storageKey;
        }
        return prefix.replaceAll("/+$", "") + "/" + storageKey;
    }

    public void validateConfig() {
        StorageProperties.R2 r2 = properties.getR2();
        require("endpoint", r2.getEndpoint());
        require("bucket", r2.getBucket());
        require("access-key", r2.getAccessKey());
        require("secret-key", r2.getSecretKey());
        try {
            URI.create(r2.getEndpoint());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("R2 endpoint 不是合法 URL：" + r2.getEndpoint(), ex);
        }
    }

    private S3Client buildClient() {
        validateConfig();
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getR2().getEndpoint()))
                .region(Region.of(properties.getR2().getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(s3Configuration())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private S3Presigner buildPresigner() {
        validateConfig();
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getR2().getEndpoint()))
                .region(Region.of(properties.getR2().getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(s3Configuration())
                .build();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.getR2().getAccessKey(), properties.getR2().getSecretKey()));
    }

    private S3Configuration s3Configuration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.getR2().isPathStyle())
                .build();
    }

    private void require(String name, String value) {
        if (value == null || value.isBlank()
                || value.trim().toLowerCase(Locale.ROOT).startsWith("${")) {
            throw ApiException.serviceUnavailable("R2 未配置：" + name);
        }
    }
}
