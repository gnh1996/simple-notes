package cc.naihao.simplenotes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 服务端版本自证（docs/release.md §2）：{@code GET /api/version} 匿名可读，
 * 四个 key 恒存在（响应 DTO 类级 requiredProperties），供管理端 / 桌面端展示与排障。
 */
class VersionControllerTest extends IntegrationTestBase {

    @Test
    @DisplayName("GET /api/version：匿名可达，恒返回 version / commit / buildTime / contract")
    void versionIsPublicAndStable() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.commit").isString())
                .andExpect(jsonPath("$.buildTime").isString())
                .andExpect(jsonPath("$.contract").value("v1"));
    }
}
