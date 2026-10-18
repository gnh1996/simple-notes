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
 * admin 管理接口集成测试（docs/auth-rbac.md §8）：用户 / 角色 / 菜单管理，
 * 内置角色与超管保护，权限码与只读语义。
 */
class AdminApiIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("用户生命周期：创建 → 列表（关键字）→ 编辑 → 分配角色 → 重置密码（撤销会话）→ 删除")
    void userLifecycle() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("worker");
        String userId = createUser(root, username, "临时用户");

        // 列表 + 关键字过滤
        var page = body(authedGet("/api/admin/users?page=1&size=10&keyword=" + username, root)
                .andExpect(status().isOk()));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("username").asString()).isEqualTo(username);
        assertThat(page.path("items").get(0).path("root").asBoolean()).isFalse();

        // 编辑：昵称 / 邮箱 / 状态
        authedPut("/api/admin/users/" + userId, root, Map.of(
                        "nickname", "改过的昵称", "email", "worker@example.com", "status", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("改过的昵称"))
                .andExpect(jsonPath("$.email").value("worker@example.com"));

        // 分配角色（先建一个带入口的角色）
        String roleId = createRole(root, uniqueRoleCode("WORKER"), "工作者");
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:user")));
        assignRoles(root, userId, List.of(roleId));
        // 重复分配同一集合：全量替换幂等（覆盖"同 PK 删除 + 重插"的 flush 顺序路径）
        assignRoles(root, userId, List.of(roleId));
        String workerToken = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");
        authedGet("/api/admin/users", workerToken).andExpect(status().isOk());

        // 重置密码：撤销被重置用户的全部会话
        authedPut("/api/admin/users/" + userId + "/password", root, Map.of("newPassword", "NewPass-123"))
                .andExpect(status().isNoContent());
        authedGet("/api/admin/users", workerToken).andExpect(status().isUnauthorized());
        login(username, DEFAULT_PASSWORD, "ADMIN").andExpect(status().isUnauthorized());
        loginAndGetToken(username, "NewPass-123", "ADMIN");

        // 删除（软删）：不可再登录；列表不再出现
        authedDelete("/api/admin/users/" + userId, root).andExpect(status().isNoContent());
        login(username, "NewPass-123", "ADMIN")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_CREDENTIALS_INVALID"));
        var afterDelete = body(authedGet("/api/admin/users?keyword=" + username, root)
                .andExpect(status().isOk()));
        assertThat(afterDelete.path("total").asInt()).isZero();

        // 软删后登录名可复用（唯一索引带 delete_flag = 0 条件）
        String recreated = createUser(root, username, "复用登录名");
        assertThat(recreated).isNotEqualTo(userId);
    }

    @Test
    @DisplayName("创建用户校验：重名 409；用户名非法规整化后拒绝")
    void createUserValidation() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("dup");
        createUser(root, username, "重名测试");
        // 大小写不同视为同一登录名（小写归一化）
        authedPost("/api/admin/users", root, Map.of(
                        "username", username.toUpperCase(), "password", DEFAULT_PASSWORD, "nickname", "x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 非法用户名（过长 / 非法字符）
        authedPost("/api/admin/users", root, Map.of(
                        "username", "bad name!", "password", DEFAULT_PASSWORD, "nickname", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 密码过短（Bean Validation）
        authedPost("/api/admin/users", root, Map.of(
                        "username", uniqueUsername("short"), "password", "short", "nickname", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("角色生命周期：创建 → 编辑 → 授权 → 占用中不可删除（409）→ 解除后删除")
    void roleLifecycle() throws Exception {
        String root = rootToken();
        String code = uniqueRoleCode("EDITOR");
        String roleId = createRole(root, code, "编辑");

        authedPut("/api/admin/roles/" + roleId, root, Map.of(
                        "name", "编辑（改）", "description", "改过的描述", "sort", 5, "status", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("编辑（改）"));

        grantRole(root, roleId, List.of(menuId("entry:blog")));
        // 重复授权同一集合：全量替换幂等（覆盖"同 PK 删除 + 重插"的 flush 顺序路径）
        grantRole(root, roleId, List.of(menuId("entry:blog")));
        var roles = body(authedGet("/api/admin/roles", root).andExpect(status().isOk()));
        var created = findRole(roles, code);
        assertThat(created).isNotNull();
        assertThat(created.path("permissionIds")).hasSize(1);

        // 有用户占用 → 409
        String username = uniqueUsername("editor");
        String userId = createUser(root, username, "编辑者");
        assignRoles(root, userId, List.of(roleId));
        authedDelete("/api/admin/roles/" + roleId, root)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 解除占用后可删除
        assignRoles(root, userId, List.of());
        authedDelete("/api/admin/roles/" + roleId, root).andExpect(status().isNoContent());
        var rolesAfter = body(authedGet("/api/admin/roles", root).andExpect(status().isOk()));
        assertThat(findRole(rolesAfter, code)).isNull();

        // 软删后角色码可复用（唯一索引带 delete_flag = 0 条件）
        String recreated = createRole(root, code, "复用角色码");
        assertThat(recreated).isNotEqualTo(roleId);
    }

    @Test
    @DisplayName("内置角色（SUPER_ADMIN）不可编辑 / 授权 / 删除 / 分配；角色码不可复用")
    void builtinRoleIsProtected() throws Exception {
        String root = rootToken();
        var superRole = findRole(body(authedGet("/api/admin/roles", root).andExpect(status().isOk())), "SUPER_ADMIN");
        assertThat(superRole).isNotNull();
        String superRoleId = superRole.path("id").asString();

        var updateBody = new java.util.HashMap<String, Object>();
        updateBody.put("name", "x");
        updateBody.put("description", null);
        updateBody.put("sort", 0);
        updateBody.put("status", 1);
        authedPut("/api/admin/roles/" + superRoleId, root, updateBody)
                .andExpect(status().isForbidden());
        authedPut("/api/admin/roles/" + superRoleId + "/permissions", root, Map.of("menuIds", List.of()))
                .andExpect(status().isForbidden());
        authedDelete("/api/admin/roles/" + superRoleId, root).andExpect(status().isForbidden());

        String username = uniqueUsername("victim");
        String userId = createUser(root, username, "受害者");
        authedPut("/api/admin/users/" + userId + "/roles", root, Map.of("roleIds", List.of(superRoleId)))
                .andExpect(status().isForbidden());

        // 新建角色不可占用内置角色码
        authedPost("/api/admin/roles", root, Map.of("code", "SUPER_ADMIN", "name", "仿冒"))
                .andExpect(status().isConflict());

        // 可分配角色排除内置角色
        var assignable = body(authedGet("/api/admin/roles/assignable", root).andExpect(status().isOk()));
        List<String> assignableCodes = new java.util.ArrayList<>();
        assignable.forEach(node -> assignableCodes.add(node.path("code").asString()));
        assertThat(assignableCodes).doesNotContain("SUPER_ADMIN");
    }

    @Test
    @DisplayName("菜单管理：改名 / 启停影响 me 菜单（可见性即时生效）；结构性字段与增删不提供")
    void menuRenameAndStatusAffectVisibleMenus() throws Exception {
        String root = rootToken();
        String id = menuId("admin:menu");
        try {
            authedPut("/api/admin/menus/" + id, root, Map.of("name", "菜单设定", "sort", 30, "status", 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("菜单设定"));

            var me = body(authedGet("/api/auth/me", root).andExpect(status().isOk()));
            var node = findMenu(me.path("menus"), "admin:menu");
            assertThat(node).isNotNull();
            assertThat(node.path("name").asString()).isEqualTo("菜单设定");

            // 停用 → 超管 me 菜单不再包含该节点（停用 = 授权不生效）
            authedPut("/api/admin/menus/" + id, root, Map.of("name", "菜单设定", "sort", 30, "status", 0))
                    .andExpect(status().isOk());
            var meAfterDisable = body(authedGet("/api/auth/me", root).andExpect(status().isOk()));
            assertThat(findMenu(meAfterDisable.path("menus"), "admin:menu")).isNull();

            // 完整目录树仍包含停用节点（管理端需要重新启用）
            var catalog = body(authedGet("/api/admin/menus", root).andExpect(status().isOk()));
            assertThat(findCatalogNode(catalog, "admin:menu").path("status").asInt()).isZero();
        } finally {
            authedPut("/api/admin/menus/" + id, root, Map.of("name", "菜单管理", "sort", 30, "status", 1))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("菜单树读取：admin:role 或 admin:menu 任一即可；改名需要 admin:menu:update")
    void menuEndpointsFollowPermissionCodes() throws Exception {
        String root = rootToken();
        String username = uniqueUsername("rolekeeper");
        String userId = createUser(root, username, "角色管理员");
        String roleId = createRole(root, uniqueRoleCode("ROLEKEEPER"), "角色维护");
        // 仅 admin:role：可读完整目录树（角色编辑器需要），不可编辑菜单
        grantRole(root, roleId, List.of(menuId("entry:admin"), menuId("admin:role")));
        assignRoles(root, userId, List.of(roleId));
        String token = loginAndGetToken(username, DEFAULT_PASSWORD, "ADMIN");

        authedGet("/api/admin/menus", token).andExpect(status().isOk());
        authedPut("/api/admin/menus/" + menuId("admin:menu"), token, Map.of(
                        "name", "x", "sort", 30, "status", 1))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("编辑不存在的用户 → 404 NOT_FOUND")
    void unknownUserUpdateReturns404() throws Exception {
        String root = rootToken();
        authedPut("/api/admin/users/not-exists-id", root, Map.of(
                        "nickname", "x", "status", 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------

    private tools.jackson.databind.JsonNode findRole(tools.jackson.databind.JsonNode roles, String code) {
        for (tools.jackson.databind.JsonNode role : roles) {
            if (code.equals(role.path("code").asString())) {
                return role;
            }
        }
        return null;
    }

    private tools.jackson.databind.JsonNode findMenu(tools.jackson.databind.JsonNode menus, String code) {
        for (tools.jackson.databind.JsonNode menu : menus) {
            if (code.equals(menu.path("code").asString())) {
                return menu;
            }
            var child = findMenu(menu.path("children"), code);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private tools.jackson.databind.JsonNode findCatalogNode(tools.jackson.databind.JsonNode nodes, String code) {
        for (tools.jackson.databind.JsonNode node : nodes) {
            if (code.equals(node.path("code").asString())) {
                return node;
            }
            var child = findCatalogNode(node.path("children"), code);
            if (child != null) {
                return child;
            }
        }
        return null;
    }
}
