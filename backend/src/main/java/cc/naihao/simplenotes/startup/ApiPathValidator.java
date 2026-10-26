package cc.naihao.simplenotes.startup;

import cc.naihao.simplenotes.web.BlogPageController;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * API 前缀启动对账（docs/blog-runtime-theme.md §5.2）：把"漏写 {@code /api} 前缀"从运行期隐患
 * 变成显式启动错误。
 *
 * <p>背景：壳路由改为 catch-all 后，{@code SecurityConfig} 用一条"非 API 的 GET 一律公开"承接
 * 任意页面路由（见 {@code BlogPageController.SHELL_PATH}）。代价是：若业务控制器漏写 {@code /api}
 * 前缀，其 GET 接口会被这条规则公开——原先靠 {@code denyAll()} 兜底的安全性消失。本校验在启动期
 * 扫描 {@link RequestMappingHandlerMapping}，要求本项目自己的 handler 全部位于 {@code /api/} 下
 * （壳控制器除外），缺失即终止启动。
 *
 * <p>只校验本项目的类：springdoc 的 {@code /v3/api-docs}、Spring Boot 的 {@code /error} 等
 * 第三方 handler 不在校验范围内。
 */
@Slf4j
@Component
public class ApiPathValidator implements ApplicationRunner {

    /** 业务接口统一前缀（docs/blog-runtime-theme.md §5.2）。 */
    private static final String API_PREFIX = "/api/";

    /** 本项目包名前缀：只对我们自己的 handler 做约束。 */
    private static final String OWN_PACKAGE = "cc.naihao.simplenotes.";

    private final RequestMappingHandlerMapping handlerMapping;

    public ApiPathValidator(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            Class<?> type = handler.getBeanType();
            if (!type.getName().startsWith(OWN_PACKAGE) || type == BlogPageController.class) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith(API_PREFIX)) {
                    violations.add(type.getSimpleName() + "#" + handler.getMethod().getName()
                            + " → " + pattern);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "API 前缀对账失败（启动终止）：非壳接口必须挂在 " + API_PREFIX + " 下，"
                            + "否则会被\"非 API 的 GET 公开\"规则放行\n- "
                            + String.join("\n- ", violations));
        }
        log.info("API 前缀对账通过：本项目业务接口均位于 {} 下，页面路由由 BlogPageController 兜底",
                API_PREFIX);
    }
}
