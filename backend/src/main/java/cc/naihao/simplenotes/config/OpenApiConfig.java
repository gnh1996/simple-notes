package cc.naihao.simplenotes.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 契约元信息（docs/tech-stack.md「API 契约」：OpenAPI 为单一来源，各端据此生成客户端）。
 *
 * <p>契约端点：{@code GET /v3/api-docs}（JSON；由 {@link SecurityConfig} 匿名放行）。
 * 导出与生成流程见 backend/AGENTS.md「API 契约」。
 */
@Configuration
public class OpenApiConfig {

    /** 契约里的认证方案名称；生成客户端据此配置 Bearer 头 */
    public static final String BEARER_SCHEME = "bearerAuth";

    /**
     * 契约代际标签（docs/release.md §1）：OpenAPI {@code info.version} 与
     * {@code GET /api/version} 的 {@code contract} 共用此常量，不是产品版本。
     */
    public static final String CONTRACT_VERSION = "v1";

    @Bean
    public OpenAPI simpleNotesOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Simple Notes API")
                        .description("随手记应用后端接口契约（认证 / RBAC 见 docs/auth-rbac.md；内容模型见 docs/content-model.md）")
                        .version(CONTRACT_VERSION))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("三端统一 Bearer 凭证（登录返回的 JWT）")));
    }
}
