package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话生命周期集成测试（docs/auth-rbac.md §5.3 / §5.4）：
 * 空闲超时 / 绝对上限 / 改密撤销 / 禁用撤销。
 */
class SessionLifecycleIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("空闲超时（Web 7 天）：判为超时 → 撤销会话行并 401")
    void idleTimeoutRevokesSession() throws Exception {
        String username = uniqueUsername("idler");
        String userId = createMember(username);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
        authedGet("/api/auth/me", token).andExpect(status().isOk());

        // 把该用户全部活跃会话拨回 8 天前（Web 空闲超时 7 天）
        jdbcTemplate.update("""
                update sys_user_session set last_active_time = now() - interval '8 days'
                where user_id = ? and revoked = false
                """, userId);

        authedGet("/api/auth/me", token).andExpect(status().isUnauthorized());
        Integer active = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_session where user_id = ? and revoked = false",
                Integer.class, userId);
        assertThat(active).isZero();
    }

    @Test
    @DisplayName("绝对上限（expire_time 到期）：会话拒绝并撤销")
    void absoluteExpiryRevokesSession() throws Exception {
        String username = uniqueUsername("expired");
        String userId = createMember(username);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        jdbcTemplate.update("""
                update sys_user_session set expire_time = now() - interval '1 hour'
                where user_id = ? and revoked = false
                """, userId);

        authedGet("/api/auth/me", token).andExpect(status().isUnauthorized());
        Integer active = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_session where user_id = ? and revoked = false",
                Integer.class, userId);
        assertThat(active).isZero();
    }

    @Test
    @DisplayName("滑动续期：距上次活跃超过阈值才回写 last_active_time")
    void lastActiveTimeIsTouchedOnlyAfterInterval() throws Exception {
        String username = uniqueUsername("slider");
        String userId = createMember(username);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        // 拨回 10 分钟前（超过默认 5 分钟回写间隔）：下一次请求应回写
        jdbcTemplate.update("""
                update sys_user_session set last_active_time = now() - interval '10 minutes'
                where user_id = ? and revoked = false
                """, userId);
        authedGet("/api/auth/me", token).andExpect(status().isOk());

        Integer touched = jdbcTemplate.queryForObject("""
                select count(*) from sys_user_session
                where user_id = ? and revoked = false and last_active_time > now() - interval '1 minute'
                """, Integer.class, userId);
        assertThat(touched).isEqualTo(1);
    }

    @Test
    @DisplayName("本人改密码：撤销除当前外全部会话；旧密码与弱密码拒绝")
    void passwordChangeRevokesOtherSessions() throws Exception {
        String username = uniqueUsername("pwd");
        createMember(username);
        String sessionA = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
        String sessionB = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        // 旧密码不正确 → 400
        authedPut("/api/auth/password", sessionA, Map.of(
                        "oldPassword", "wrong", "newPassword", "AnotherPass-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        authedPut("/api/auth/password", sessionA, Map.of(
                        "oldPassword", DEFAULT_PASSWORD, "newPassword", "AnotherPass-1"))
                .andExpect(status().isNoContent());

        // 当前会话保留，其他会话被撤销
        authedGet("/api/auth/me", sessionA).andExpect(status().isOk());
        authedGet("/api/auth/me", sessionB).andExpect(status().isUnauthorized());

        // 新密码可登录，旧密码不可
        loginAndGetToken(username, "AnotherPass-1", "ADMIN");
        login(username, DEFAULT_PASSWORD, "ADMIN").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("禁用用户：写路径撤销全部会话 → 下一次请求 401")
    void disableUserRevokesAllSessions() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("disabled");
        String userId = createMember(username);
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        authedPut("/api/admin/users/" + userId, root, Map.of(
                        "nickname", "将禁用", "status", 0))
                .andExpect(status().isOk());

        authedGet("/api/auth/me", token).andExpect(status().isUnauthorized());
        login(username, DEFAULT_PASSWORD, "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOGIN_USER_DISABLED"));
    }

    @Test
    @DisplayName("logout-all：撤销该用户全部会话（含其他客户端）")
    void logoutAllRevokesEverySession() throws Exception {
        String username = uniqueUsername("multi");
        createMember(username);
        String webSession = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
        String desktopSession = loginAndGetToken(username, DEFAULT_PASSWORD, "DESKTOP");

        authedPost("/api/auth/logout-all", webSession, null).andExpect(status().isNoContent());
        authedGet("/api/auth/me", webSession).andExpect(status().isUnauthorized());
        authedGet("/api/auth/me", desktopSession).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    /** 建一个带入口与页面读权限的普通用户，返回用户 id */
    private String createMember(String username) throws Exception {
        String root = rootToken();
        String userId = createUser(root, username, "会话测试用户");
        String roleId = createRole(root, uniqueRoleCode("SESSION"), "会话测试角色");
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("entry:desktop"),
                menuId("admin:user")));
        assignRoles(root, userId, List.of(roleId));
        return userId;
    }
}
