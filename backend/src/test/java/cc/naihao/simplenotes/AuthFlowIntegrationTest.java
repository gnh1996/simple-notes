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
 * 认证主链路集成测试（docs/auth-rbac.md §5）：
 * 登录语义 / 限流 / 入口校验 / 会话校验 / 端一致性 / 即时生效与即时回收。
 */
class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("未携带凭证访问受保护接口 → 401 AUTH_REQUIRED")
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        authedGet("/api/admin/users", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("伪造 / 畸形凭证 → 401（不查库快速失败）")
    void malformedTokenReturns401() throws Exception {
        authedGet("/api/admin/users", "not-a-real-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("凭据错误 → 401 LOGIN_CREDENTIALS_INVALID；账号不存在同样处理（不泄露用户存在性）")
    void wrongPasswordReturnsCredentialsInvalid() throws Exception {
        login(ROOT_USERNAME, "wrong-password", "ADMIN")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_CREDENTIALS_INVALID"));
        login(uniqueUsername("ghost"), "whatever", "ADMIN")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_CREDENTIALS_INVALID"));
    }

    @Test
    @DisplayName("连续 5 次失败 → 锁定 60 秒，第 6 次返回 429 LOGIN_RATE_LIMITED")
    void loginRateLimitedAfterConsecutiveFailures() throws Exception {
        String victim = uniqueUsername("ratelimit");
        for (int i = 0; i < 5; i++) {
            login(victim, "wrong-" + i, "ADMIN")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_CREDENTIALS_INVALID"));
        }
        login(victim, "wrong-again", "ADMIN")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    @DisplayName("root 登录（迁移内置初始密码）→ me 返回 root=true 与全量菜单（不做过滤）")
    void rootLoginAndMe() throws Exception {
        String token = rootToken();
        authedGet("/api/auth/me", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value(true))
                .andExpect(jsonPath("$.user.username").value(ROOT_USERNAME))
                // 超管能力不落在权限码集合里：codes / entries 均为空
                .andExpect(jsonPath("$.codes").isEmpty())
                .andExpect(jsonPath("$.entries").isEmpty())
                // 全量菜单（不做过滤）：系统域 + 内容域共六个 admin 菜单节点
                .andExpect(jsonPath("$.menus.length()").value(6))
                .andExpect(jsonPath("$.menus[0].code").value("admin:user"))
                .andExpect(jsonPath("$.menus[0].children.length()").value(0));
    }

    @Test
    @DisplayName("登出 → 当前会话立即失效（401）")
    void logoutRevokesSession() throws Exception {
        String token = rootToken();
        authedPost("/api/auth/logout", token, null).andExpect(status().isNoContent());
        authedGet("/api/auth/me", token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("会话端一致性：admin 凭证不适用于 blog 域（公开读按匿名放行、写接口 401）")
    void adminTokenDoesNotApplyToBlogDomain() throws Exception {
        String token = rootToken();
        // blog 公开读（匿名可访问）：凭证不适用 → 按匿名处理 → 资源不存在 404
        authedGet("/api/blog/not-exists", token).andExpect(status().isNotFound());
        // blog 写操作（要求会话）：凭证不适用 → 401
        authedPost("/api/blog/not-exists", token, Map.of())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("完整授权链路：入口校验 / 只读与写权限分离 / 授权变更即时生效 / 入口回收即时撤销")
    void permissionLifecycleTakesEffectImmediately() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("member");
        String userId = createUser(root, username, "创作人");
        String roleId = createRole(root, uniqueRoleCode("MEMBER"), "创作人角色");

        // 1) 无任何角色 → 无入口权限，登录拒绝
        login(username, DEFAULT_PASSWORD, "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOGIN_ENTRY_DENIED"));

        // 2) 仅有页面读权限（admin:user）但无入口 → 仍拒绝
        grantRole(root, roleId, List.of(menuId("admin:user")));
        assignRoles(root, userId, List.of(roleId));
        login(username, DEFAULT_PASSWORD, "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOGIN_ENTRY_DENIED"));

        // 3) 授予入口 + 页面读权限 → 登录成功；读接口放行、写接口拒绝
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:user")));
        String member = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
        authedGet("/api/admin/users", member).andExpect(status().isOk());
        authedPost("/api/admin/users", member, Map.of(
                        "username", uniqueUsername("denied"), "password", DEFAULT_PASSWORD, "nickname", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        // 4) 追加写权限 → 同一 token 下一次请求即生效（不重发 token、不踢线）
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:user"),
                menuId("admin:user:create")));
        authedPost("/api/admin/users", member, Map.of(
                        "username", uniqueUsername("allowed"), "password", DEFAULT_PASSWORD, "nickname", "x"))
                .andExpect(status().isOk());

        // 5) 收回入口 → 下一次请求即被踢（会话撤销 + 401）
        grantRole(root, roleId, List.of(menuId("admin:user")));
        authedGet("/api/admin/users", member).andExpect(status().isUnauthorized());
        // 会话行已被撤销：重新请求同样 401
        authedGet("/api/auth/me", member).andExpect(status().isUnauthorized());

        // 6) 重新授予入口后可正常登录（登录入口语义恢复）
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:user")));
        loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
    }

    @Test
    @DisplayName("可见菜单树：仅授权节点及其 MENU 祖先可见；ACTION 不出现在菜单载荷中")
    void visibleMenuTreeFollowsGrants() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("viewer");
        String userId = createUser(root, username, "访客");
        String roleId = createRole(root, uniqueRoleCode("VIEWER"), "访客角色");

        // 只勾选一个 ACTION（admin:user:create）：其所在页面 admin:user 应可见（勾选即显示）
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:user:create")));
        assignRoles(root, userId, List.of(roleId));
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        var me = body(authedGet("/api/auth/me", token).andExpect(status().isOk()));
        var menus = me.path("menus");
        assertThat(menus.size()).isEqualTo(1);
        assertThat(menus.get(0).path("code").asString()).isEqualTo("admin:user");
        // ACTION 不出现在菜单载荷中
        assertThat(menus.get(0).path("children").size()).isZero();
        // codes 含两条授权码（entry + action）
        assertThat(me.path("codes")).hasSize(2);
        assertThat(me.path("entries")).hasSize(1);
    }

    @Test
    @DisplayName("me 的 menus 仅 ADMIN 端返回（blog / desktop 无菜单载荷）")
    void menusOnlyReturnedForAdminClient() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("deskuser");
        String userId = createUser(root, username, "桌面用户");
        String roleId = createRole(root, uniqueRoleCode("DESKUSER"), "桌面角色");
        grantRole(root, roleId, List.of(menuId("entry:desktop")));
        assignRoles(root, userId, List.of(roleId));

        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "DESKTOP");
        var me = body(authedGet("/api/auth/me", token).andExpect(status().isOk()));
        assertThat(me.path("menus").isMissingNode()).isTrue();
        assertThat(me.path("entries").get(0).asString()).isEqualTo("entry:desktop");
    }

    @Test
    @DisplayName("root 用户不可被删除 / 禁用 / 改角色集合 / 重置密码（接口拒绝）")
    void superAdminHolderIsProtected() throws Exception {
        String root = rootToken();
        String rootUserId = meUserId(root);

        authedPut("/api/admin/users/" + rootUserId, root, Map.of(
                        "nickname", "管理员", "status", 0))
                .andExpect(status().isForbidden());
        authedDelete("/api/admin/users/" + rootUserId, root).andExpect(status().isForbidden());
        authedPut("/api/admin/users/" + rootUserId + "/roles", root, Map.of("roleIds", List.of()))
                .andExpect(status().isForbidden());
        authedPut("/api/admin/users/" + rootUserId + "/password", root, Map.of("newPassword", "Whatever-1"))
                .andExpect(status().isForbidden());
    }
}
