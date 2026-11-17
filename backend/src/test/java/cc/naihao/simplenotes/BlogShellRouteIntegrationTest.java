package cc.naihao.simplenotes;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/**
 * 博客壳的 history fallback（docs/blog-runtime-theme.md §4.5）：
 * 页面路由归 runtime，后端只保证"任意非保留前缀的 GET 返回同一份壳"。
 *
 * <p>锁定三件事：① 新增前端路由不需要改后端；② 壳不会抢占 {@code /blog/**} 静态资源；
 * ③ 未知 API 仍然是 JSON（401 / 404），不会退化成 HTML。
 */
class BlogShellRouteIntegrationTest extends IntegrationTestBase {

    @ParameterizedTest(name = "GET {0} → 壳")
    @ValueSource(strings = {
            "/",
            "/posts/hello",
            "/tags",
            "/tags/abc",
            "/archives",
            "/search",
            "/about",
            "/p/about",
            // 以下路径 runtime 尚未登记，也必须返回壳（由 runtime 渲染 not-found）
            "/categories",
            "/any/new/route/deep"
    })
    void everyNonReservedGetReturnsTheShell(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("window.__BLOG_BOOT__")))
                .andExpect(content().string(containsString("data-blog-boot")))
                .andExpect(content().string(containsString("data-blog-progress")))
                // 管理端 origin 不再进入公开博客；预览已迁到 admin 同源 /__preview
                .andExpect(content().string(not(containsString("adminOrigin"))))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Content-Security-Policy",
                        not(containsString("frame-ancestors 'self' http"))));
    }

    @Test
    @DisplayName("公开 bootstrap 不返回管理端 origin")
    void publicBootstrapDoesNotExposeAdminOrigin() throws Exception {
        mockMvc.perform(get("/api/blog/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("adminOrigin"))));
    }

    @Test
    @DisplayName("admin 同源预览路由：router base=/__preview，且不暴露管理端 origin")
    void previewRouteUsesAdminBasePath() throws Exception {
        mockMvc.perform(get("/__preview"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"basePath\":\"/__preview\"")))
                .andExpect(content().string(not(containsString("adminOrigin"))))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors 'self'")));
    }

    @Test
    @DisplayName("HEAD 与 GET 同权：公开只读资源放行，管理接口仍要求会话")
    void headMatchesGetForPublicReads() throws Exception {
        mockMvc.perform(head("/")).andExpect(status().isOk());
        mockMvc.perform(head("/api/version")).andExpect(status().isOk());
        mockMvc.perform(head("/api/blog/posts").param("page", "1")).andExpect(status().isOk());
        mockMvc.perform(head("/api/media/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
        mockMvc.perform(head("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("壳内联首屏加载层：就绪后淡出，且错误可以强制显示")
    void shellRendersBootLoader() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("html[data-blog-ready='1'] [data-blog-boot]")))
                .andExpect(content().string(containsString("正在加载")));
    }

    @Test
    @DisplayName("GET /blog/**：静态资源不被壳抢占（缺构建产物时是 404 JSON，而不是 HTML）")
    void runtimeAssetIsNotSwallowedByShell() throws Exception {
        mockMvc.perform(get("/blog/runtime.js"))
                .andExpect(content().string(not(containsString("window.__BLOG_BOOT__"))));
    }

    @Test
    @DisplayName("GET /favicon.svg：根静态文件仍然可达")
    void faviconIsStillServed() throws Exception {
        mockMvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未知 API：匿名 401、已认证 404，且始终是 JSON")
    void unknownApiStaysJson() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        authedGet("/api/does-not-exist", rootToken())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("放宽 GET /** 后，管理端接口仍然要求认证")
    void adminApiIsNotOpenedByShellRule() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }
}
