package cc.naihao.simplenotes.startup;

import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.MenuType;
import cc.naihao.simplenotes.domain.SysMenu;
import cc.naihao.simplenotes.repository.SysMenuRepository;
import cc.naihao.simplenotes.security.Perms;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限目录启动对账（docs/auth-rbac.md §3.4，关键补强）：把"双源漂移"从静默故障变为显式启动错误。
 *
 * <ol>
 *   <li><b>缺失 fail-fast</b>：扫描 {@link Perms} 全部常量，逐一核对 {@code sys_menu} 存在对应节点，
 *       缺失则列出全部缺失码并终止启动；</li>
 *   <li><b>结构校验</b>：权限码前缀 ↔ client 一致、ACTION 不得有子节点、ENTRY 为根且每端唯一、
 *       父子同端（与 FK / 唯一索引共同保证结构约束）；</li>
 *   <li><b>注解对账</b>：扫描全部 {@code @RestController} 的 {@code @PreAuthorize} 字面量码，
 *       必须已在 {@link Perms} 声明（防注解手写偏差）→ 结合第 1 条自然保证其存在于库中。</li>
 * </ol>
 *
 * <p>反向（表中有节点、代码未引用）不阻塞启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuCatalogValidator implements ApplicationRunner {

    /** 权限码形态：{端}:{模块}[:{资源}][:{动作}]，如 admin:user、admin:user:assign-role */
    private static final Pattern PERMISSION_CODE = Pattern.compile("[a-z][a-z0-9-]*(:[a-z0-9-]+)+");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

    private final SysMenuRepository menuRepository;
    private final ApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = new ArrayList<>();
        List<SysMenu> nodes = menuRepository.findAll();
        Map<String, SysMenu> byCode = nodes.stream()
                .collect(Collectors.toMap(SysMenu::getCode, Function.identity()));

        validateDeclaredCodes(byCode, problems);
        validateStructure(nodes, problems);
        validatePreAuthorizeAnnotations(problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("权限目录对账失败（启动终止）：\n- " + String.join("\n- ", problems));
        }
        log.info("权限目录对账通过：Perms 声明 {} 个权限码，sys_menu 共 {} 个节点", Perms.all().size(), nodes.size());
    }

    /** 1) Perms 常量 → 库中存在对应节点 */
    private void validateDeclaredCodes(Map<String, SysMenu> byCode, List<String> problems) {
        for (String code : Perms.all()) {
            if (!byCode.containsKey(code)) {
                problems.add("权限目录缺少节点（Perms 已声明、sys_menu 无对应行）：" + code);
            }
        }
    }

    /** 2) 结构校验 */
    private void validateStructure(List<SysMenu> nodes, List<String> problems) {
        Map<String, SysMenu> byId = nodes.stream()
                .collect(Collectors.toMap(SysMenu::getId, Function.identity()));

        for (SysMenu node : nodes) {
            String code = node.getCode();
            String clientName = node.getClient().name().toLowerCase(Locale.ROOT);

            // 权限码前缀 ↔ client 一致（ENTRY 码形如 entry:admin，其余以端前缀开头）
            if (node.getType() == MenuType.ENTRY) {
                if (!code.equals("entry:" + clientName)) {
                    problems.add("ENTRY 权限码与 client 不一致：" + code + "（client=" + node.getClient() + "）");
                }
            } else if (!code.startsWith(clientName + ":")) {
                problems.add("权限码前缀与 client 不一致：" + code + "（client=" + node.getClient() + "）");
            }

            // ENTRY 为根；非 ENTRY 必须有父节点
            if (node.getType() == MenuType.ENTRY && node.getParentId() != null) {
                problems.add("ENTRY 节点必须是根（parent 为空）：" + code);
            }
            if (node.getType() != MenuType.ENTRY && node.getParentId() == null) {
                problems.add("非 ENTRY 节点必须有父节点：" + code);
            }

            // 父节点存在且同端
            if (node.getParentId() != null) {
                SysMenu parent = byId.get(node.getParentId());
                if (parent == null) {
                    problems.add("父节点不存在：" + code);
                } else if (parent.getClient() != node.getClient()) {
                    problems.add("父子节点跨端：" + code);
                }
            }

            // ACTION 不得有子节点
            if (node.getType() == MenuType.ACTION
                    && nodes.stream().anyMatch(other -> node.getId().equals(other.getParentId()))) {
                problems.add("ACTION 节点不得有子节点：" + code);
            }
        }

        // 每端 ENTRY 唯一
        for (ClientType client : ClientType.values()) {
            long entryCount = nodes.stream()
                    .filter(node -> node.getClient() == client && node.getType() == MenuType.ENTRY)
                    .count();
            if (entryCount != 1) {
                problems.add(client + " 端 ENTRY 节点数量应为 1，实际 " + entryCount);
            }
        }
    }

    /** 3) @PreAuthorize 注解引用的码必须已在 Perms 声明 */
    private void validatePreAuthorizeAnnotations(List<String> problems) {
        Map<String, Object> controllers = applicationContext.getBeansWithAnnotation(RestController.class);
        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            Class<?> targetClass = AopUtils.getTargetClass(entry.getValue());
            for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
                PreAuthorize annotation = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
                if (annotation != null) {
                    checkExpression(annotation.value(), describe(targetClass, method), problems);
                }
            }
        }
    }

    private void checkExpression(String expression, String location, List<String> problems) {
        Matcher matcher = QUOTED_LITERAL.matcher(expression);
        while (matcher.find()) {
            String literal = matcher.group(1);
            if (PERMISSION_CODE.matcher(literal).matches() && !Perms.isDeclared(literal)) {
                problems.add("注解引用了未在 Perms 声明的权限码：" + literal + "（" + location + "）");
            }
        }
    }

    private String describe(Class<?> targetClass, Method method) {
        return targetClass.getSimpleName() + "#" + method.getName();
    }
}
