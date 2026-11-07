package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.config.OpenApiConfig;
import cc.naihao.simplenotes.web.dto.VersionResponse;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务端版本自证（docs/release.md §2）：{@code GET /api/version}，匿名可读。
 *
 * <p>版本来源优先级：
 * <ol>
 *   <li>{@code META-INF/build-info.properties}（发布构建经 {@code -Drevision} 烘焙）；</li>
 *   <li>环境变量 {@code APP_VERSION}（容器 / 部署覆盖）；</li>
 *   <li>回退 {@code 0.0.0-dev}（IDE 直接运行且无 build-info 时）。</li>
 * </ol>
 * commit 与 buildTime 同理（{@code APP_COMMIT} / {@code APP_BUILD_TIME}，
 * buildTime 优先取 build-info 的构建时刻）。
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    /** 1980 年可复现构建默认值与真实构建时刻的分界：早于此即视为未设置。 */
    private static final Instant REALISTIC_CUTOFF = Instant.parse("2020-01-01T00:00:00Z");

    private final ObjectProvider<BuildProperties> buildProperties;    private final String appVersion;
    private final String appCommit;
    private final String appBuildTime;

    public VersionController(
            ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
        this.appVersion = env("APP_VERSION", "");
        this.appCommit = env("APP_COMMIT", "unknown");
        this.appBuildTime = env("APP_BUILD_TIME", "unknown");
    }

    @GetMapping
    public VersionResponse getVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : null;
        if (version == null || version.isBlank()) {
            version = appVersion.isBlank() ? "0.0.0-dev" : appVersion;
        }
        return new VersionResponse(version, appCommit, buildTime(build), OpenApiConfig.CONTRACT_VERSION);
    }

    /**
     * 构建时刻的优先级：显式传入的 {@code APP_BUILD_TIME}（部署兜底）>
     * build-info 烘焙时刻（发布构建经 {@code -Dproject.build.outputTimestamp} 传入真实时刻）>
     * {@code unknown}。
     *
     * <p>本地构建的 build-info 时刻是 Maven 可复现构建默认值（1980 年），不是真实构建时刻，
     * 按"早于 2020 即视为未设置"过滤，避免在关于页展示误导时间。
     */
    private String buildTime(BuildProperties build) {
        if (!appBuildTime.isBlank() && !"unknown".equals(appBuildTime)) {
            return appBuildTime;
        }
        if (build != null && build.getTime() != null && build.getTime().isAfter(REALISTIC_CUTOFF)) {
            return build.getTime().toString();
        }
        return "unknown";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
