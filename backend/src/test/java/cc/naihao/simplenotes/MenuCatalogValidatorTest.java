package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.MenuType;
import cc.naihao.simplenotes.domain.SysMenu;
import cc.naihao.simplenotes.repository.SysMenuRepository;
import cc.naihao.simplenotes.security.Perms;
import cc.naihao.simplenotes.startup.MenuCatalogValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限目录启动对账（fail-fast）单元测试（docs/auth-rbac.md §3.4）。
 */
class MenuCatalogValidatorTest {

    private SysMenuRepository menuRepository;
    private ApplicationContext applicationContext;
    private MenuCatalogValidator validator;

    @BeforeEach
    void setUp() {
        menuRepository = mock(SysMenuRepository.class);
        applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(any())).thenReturn(Map.of());
        validator = new MenuCatalogValidator(menuRepository, applicationContext);
    }

    @Test
    @DisplayName("完整目录 + 结构合法 → 对账通过")
    void validCatalogPasses() {
        when(menuRepository.findAll()).thenReturn(validCatalog());
        validator.run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    @DisplayName("Perms 声明的码在 sys_menu 缺失 → 启动失败并列缺失码")
    void missingDeclaredCodeFailsFast() {
        List<SysMenu> nodes = validCatalog();
        nodes.removeIf(node -> node.getCode().equals(Perms.ADMIN_USER_CREATE));
        when(menuRepository.findAll()).thenReturn(nodes);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限目录对账失败")
                .hasMessageContaining(Perms.ADMIN_USER_CREATE);
    }

    @Test
    @DisplayName("ACTION 节点挂子节点 → 结构校验失败")
    void actionWithChildFailsFast() {
        List<SysMenu> nodes = validCatalog();
        SysMenu action = findByCode(nodes, Perms.ADMIN_USER_CREATE);
        nodes.add(node("99999999-0000-0000-0000-000000000001", action.getId(), ClientType.ADMIN,
                MenuType.MENU, "admin:user:illegal-child"));
        when(menuRepository.findAll()).thenReturn(nodes);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTION 节点不得有子节点：" + Perms.ADMIN_USER_CREATE);
    }

    @Test
    @DisplayName("权限码前缀与 client 不一致 → 结构校验失败")
    void clientPrefixMismatchFailsFast() {
        List<SysMenu> nodes = validCatalog();
        findByCode(nodes, Perms.ADMIN_USER).setClient(ClientType.BLOG);
        when(menuRepository.findAll()).thenReturn(nodes);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限码前缀与 client 不一致：" + Perms.ADMIN_USER);
    }

    @Test
    @DisplayName("ENTRY 不是根 / 每端唯一性被破坏 → 结构校验失败")
    void entryStructureFailsFast() {
        List<SysMenu> nodes = validCatalog();
        findByCode(nodes, Perms.ENTRY_BLOG).setParentId("00000000-0000-0000-0000-000000000010");
        when(menuRepository.findAll()).thenReturn(nodes);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENTRY 节点必须是根");
    }

    @Test
    @DisplayName("注解引用了 Perms 未声明的权限码 → 启动失败（防手写偏差）")
    void undeclaredAnnotationCodeFailsFast() {
        when(menuRepository.findAll()).thenReturn(validCatalog());
        when(applicationContext.getBeansWithAnnotation(RestController.class))
                .thenReturn(Map.of("testController", new UndeclaredPermissionController()));

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注解引用了未在 Perms 声明的权限码：nope:code");
    }

    // ------------------------------------------------------------------

    /** 按 Perms 常量与权限码层级生成一份结构合法的目录（ENTRY → MENU → ACTION） */
    private static List<SysMenu> validCatalog() {
        List<SysMenu> nodes = new ArrayList<>();
        Map<String, String> idByCode = new HashMap<>();
        Map<ClientType, String> entryIdByClient = new EnumMap<>(ClientType.class);
        int sequence = 0;

        for (String code : Perms.all()) {
            if (code.startsWith("entry:")) {
                ClientType client = ClientType.valueOf(code.substring("entry:".length()).toUpperCase());
                SysMenu entry = node(id(sequence++), null, client, MenuType.ENTRY, code);
                nodes.add(entry);
                idByCode.put(code, entry.getId());
                entryIdByClient.put(client, entry.getId());
            }
        }
        for (String code : Perms.all()) {
            if (code.startsWith("entry:")) {
                continue;
            }
            String clientName = code.substring(0, code.indexOf(':'));
            ClientType client = ClientType.valueOf(clientName.toUpperCase());
            String[] parts = code.split(":");
            boolean action = parts.length == 3;
            String parentCode = action ? parts[0] + ":" + parts[1] : "entry:" + clientName;
            SysMenu node = node(id(sequence++),
                    action ? idByCode.get(parentCode) : entryIdByClient.get(client),
                    client, action ? MenuType.ACTION : MenuType.MENU, code);
            nodes.add(node);
            idByCode.put(code, node.getId());
        }
        return nodes;
    }

    private static SysMenu findByCode(List<SysMenu> nodes, String code) {
        return nodes.stream().filter(node -> node.getCode().equals(code)).findFirst().orElseThrow();
    }

    private static SysMenu node(String id, String parentId, ClientType client, MenuType type, String code) {
        SysMenu node = new SysMenu();
        node.setId(id);
        node.setParentId(parentId);
        node.setClient(client);
        node.setType(type);
        node.setCode(code);
        node.setName(code);
        node.setSort(1);
        node.setStatus((short) 1);
        node.setCreateTime(Instant.now());
        return node;
    }

    private static String id(int sequence) {
        return String.format("00000000-0000-0000-0000-%012d", sequence);
    }

    @RestController
    static class UndeclaredPermissionController {
        @PreAuthorize("@authz.can('nope:code')")
        public void endpoint() {
        }
    }
}
