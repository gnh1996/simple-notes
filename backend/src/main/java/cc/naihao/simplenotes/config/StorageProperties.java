package cc.naihao.simplenotes.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 附件存储配置（{@code simple-notes.storage.*}，docs/attachment-storage.md）。
 *
 * <p>数据库中的 {@code site_setting.storage.mode} 是运行时模式来源；本配置里的 {@code mode}
 * 只作为该键缺失时的兜底。R2 凭据只从环境变量 / application-local.yml 提供，不落库。
 */
@ConfigurationProperties(prefix = "simple-notes.storage")
@Getter
@Setter
public class StorageProperties {

    /** 全局单文件上限，默认 20 MB。 */
    private long maxBytes = 20L * 1024 * 1024;

    /** 媒体公开 origin；空表示返回相对 URL。独立媒体域时填 https://media.example.com。 */
    private String publicBaseUrl = "";

    /** DIRECT：OSS 返回 /oss/{key}；STABLE：恒返回 /api/media/{id}。 */
    private UrlMode urlMode = UrlMode.DIRECT;

    /** site_setting 缺失时的兜底模式。 */
    private String mode = "LOCAL";

    /** Nginx auth_request 内部签名接口的共享令牌；为空时内部接口拒绝所有请求。 */
    private String internalToken;

    /** 本地存储根目录（仅 LOCAL 模式）。 */
    private String localRoot = System.getProperty("java.io.tmpdir") + "/simple-notes-media";

    private Database database = new Database();
    private R2 r2 = new R2();

    public enum UrlMode {
        DIRECT,
        STABLE
    }

    @Getter
    @Setter
    public static class Database {

        /** DATABASE 模式单文件上限，默认 20 MB。 */
        private long maxBytes = 20L * 1024 * 1024;
    }

    @Getter
    @Setter
    public static class R2 {

        /** S3 API endpoint，例如 https://<account>.r2.cloudflarestorage.com。 */
        private String endpoint;

        private String region = "auto";
        private String bucket = "simple-notes";
        private String accessKey;
        private String secretKey;
        private boolean pathStyle = true;
        private String keyPrefix = "attachments";

        /** 预签名 URL 有效期，默认 5 分钟。 */
        private Duration presignTtl = Duration.ofMinutes(5);

        /**
         * auth-request：Nginx auth_request 调后端签名后代理（B，默认）；
         * backend-302：后端直接 302 到预签名 R2（A）；
         * backend-proxy：后端从 R2 流式返回（本地开发 / 无 Nginx）。
         */
        private String signing = "auth-request";

        /** nginx / backend；仅影响 /api/media/{id} 的 OSS 稳定端点行为。 */
        private String edge = "nginx";

        /** 公开 bucket / 自定义域，仅用于 Nginx upstream；后端不使用。 */
        private String publicBaseUrl;
    }
}
