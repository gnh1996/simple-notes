package cc.naihao.simplenotes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI 契约端点（docs/tech-stack.md「API 契约」）：
 * 契约是各端生成客户端的唯一来源，此处锁定"匿名可访问 + 关键结构存在"，
 * 避免安全配置或 springdoc 变更导致前端生成链路静默失效。
 */
class OpenApiContractIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("GET /v3/api-docs：匿名可访问，含 Bearer 方案、响应 DTO required 标记与业务路径")
    void apiDocsIsAnonymouslyAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                // 响应 DTO 的 required 由 @Schema(requiredProperties) 声明（生成端 TS 依赖）
                .andExpect(jsonPath("$.components.schemas.UserResponse.required").isArray())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.id").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists());
    }
}
