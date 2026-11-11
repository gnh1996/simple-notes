package cc.naihao.simplenotes.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.repository.SysMenuRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：Testcontainers PostgreSQL + 全量上下文 + MockMvc。
 *
 * <p>所有集成测试共享同一容器与上下文（注解一致 → 上下文缓存），数据库在测试运行开始时由
 * Flyway V1 初始化；测试数据一律用唯一名，互不干扰。
 */
@SpringBootTest(properties = {
        "simple-notes.auth.jwt-secret=integration-test-secret-0123456789abcdef0123456789abcdef",
        // 迁移预置口令与 ROOT_PASSWORD 相同：初始化器会重写同值 hash，登录不受影响；
        // 缺这个属性会触发 RootPasswordInitializer 的 fail-fast，整个上下文起不来
        "simple-notes.auth.root-initial-password=admin@123456"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public abstract class IntegrationTestBase {

    protected static final String ROOT_USERNAME = "root";
    /** 迁移内置的固定初始密码（docs/auth-rbac.md §4） */
    protected static final String ROOT_PASSWORD = "admin@123456";
    protected static final String DEFAULT_PASSWORD = "Passw0rd!";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected SysMenuRepository menuRepository;

    // ------------------------------------------------------------------
    // 请求辅助
    // ------------------------------------------------------------------

    protected ResultActions authedGet(String path, String token) throws Exception {
        return mockMvc.perform(authorize(get(path), token));
    }

    protected ResultActions authedPost(String path, String token, Object body) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(json(body));
        }
        return mockMvc.perform(authorize(builder, token));
    }

    protected ResultActions authedPut(String path, String token, Object body) throws Exception {
        var builder = put(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(json(body));
        }
        return mockMvc.perform(authorize(builder, token));
    }

    protected ResultActions authedDelete(String path, String token) throws Exception {
        return mockMvc.perform(authorize(delete(path), token));
    }

    protected String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private <T extends MockHttpServletRequestBuilder> T authorize(T builder, String token) {
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder;
    }

    // ------------------------------------------------------------------
    // 认证 / 数据准备辅助
    // ------------------------------------------------------------------

    protected ResultActions login(String username, String password, String client) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", password, "client", client))));
    }

    /** 登录成功并取出 JWT；失败则抛断言异常 */
    protected String loginAndGetToken(String username, String password, String client) throws Exception {
        return body(login(username, password, client).andExpect(status().isOk())).path("token").asString();
    }

    protected String rootToken() throws Exception {
        return loginAndGetToken(ROOT_USERNAME, ROOT_PASSWORD, "ADMIN");
    }

    protected String meUserId(String token) throws Exception {
        return body(authedGet("/api/auth/me", token).andExpect(status().isOk()))
                .path("user").path("id").asString();
    }

    /** 管理端创建用户（断言 200），返回 id */
    protected String createUser(String token, String username, String nickname) throws Exception {
        return body(authedPost("/api/admin/users", token, Map.of(
                        "username", username, "password", DEFAULT_PASSWORD, "nickname", nickname))
                .andExpect(status().isOk())).path("id").asString();
    }

    /** 管理端创建角色（断言 200），返回 id */
    protected String createRole(String token, String code, String name) throws Exception {
        return body(authedPost("/api/admin/roles", token, Map.of(
                        "code", code, "name", name, "description", "集成测试角色", "sort", 100))
                .andExpect(status().isOk())).path("id").asString();
    }

    /** 角色授权（全量替换，断言 200） */
    protected void grantRole(String token, String roleId, List<String> menuIds) throws Exception {
        authedPut("/api/admin/roles/" + roleId + "/permissions", token, Map.of("menuIds", menuIds))
                .andExpect(status().isOk());
    }

    /** 分配角色（全量替换，断言 200） */
    protected void assignRoles(String token, String userId, List<String> roleIds) throws Exception {
        authedPut("/api/admin/users/" + userId + "/roles", token, Map.of("roleIds", roleIds))
                .andExpect(status().isOk());
    }

    protected String menuId(String code) {
        return menuRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("权限目录不存在节点：" + code))
                .getId();
    }

    /** 唯一后缀（用户名 / 角色码），避免测试间相互影响 */
    protected static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    protected static String uniqueUsername(String prefix) {
        return prefix + "-" + uniqueSuffix();
    }

    /** 角色码：[A-Z][A-Z0-9_]* */
    protected static String uniqueRoleCode(String prefix) {
        return (prefix + "_" + uniqueSuffix()).toUpperCase();
    }
}
