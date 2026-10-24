package cc.naihao.simplenotes.config;

import cc.naihao.simplenotes.security.DbUserDetailsService;
import cc.naihao.simplenotes.security.RestAccessDeniedHandler;
import cc.naihao.simplenotes.security.RestAuthenticationEntryPoint;
import cc.naihao.simplenotes.security.SessionJwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（docs/auth-rbac.md §6.1 接口域与匿名边界）。
 *
 * <ul>
 *   <li>三端统一 Bearer 凭证（无 Cookie / CSRF 面），服务端会话状态由 sys_user_session 承载，
 *       因此安全上下文不落 HttpSession（STATELESS）；</li>
 *   <li>路径域规则：{@code /api/auth/**} 除登录外要求会话；{@code /api/admin/**}、
 *       {@code /api/desktop/**} 要求会话；{@code /api/blog/**} 公开读匿名、其余要求会话；
 *       非 API 路径与未知 API 默认拒绝；</li>
 *   <li>细粒度权限（权限码）由方法级 {@code @PreAuthorize("@authz.can(...)")} 逐接口声明，
 *       超管角色短路；</li>
 *   <li>401 / 403 统一 JSON 出口，与业务错误体一致。</li>
 * </ul>
 *
 * <p>注意：会话<b>端一致性</b>（如 blog 会话不得调 admin 接口）在 {@link SessionJwtFilter} 中校验，
 * 因为它需要已解析的会话行。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({ AuthProperties.class, SiteProperties.class })
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionJwtFilter sessionJwtFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /** DelegatingPasswordEncoder：存储格式 {bcrypt}$2a$...，为将来更换算法留余地 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 登录端点的标准认证入口（docs/auth-rbac.md §5.1）：{@link DbUserDetailsService} +
     * {@link DaoAuthenticationProvider}（密码比对 / 账号状态 / 时序缓解）装配为 AuthenticationManager。
     *
     * <p>过滤器链不使用它：请求认证走 {@link SessionJwtFilter} + 授权快照（§5.3）。
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Spring Boot 错误分发路径：保证异常响应不被授权规则二次拦截
                        .requestMatchers("/error").permitAll()
                        // OpenAPI 契约（docs/tech-stack.md：契约单一来源，前端 / 桌面端生成客户端用）
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // 登录匿名可访问；其余认证接口（logout / me / password / profile）要求会话
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // blog 公开读接口匿名可访问；写操作要求会话（blog:* 权限码随 blog 功能设计补充）
                        .requestMatchers(HttpMethod.GET, "/api/blog/**").permitAll()
                        // 稳定媒体 URL 匿名可读（正文 / 封面引用）
                        .requestMatchers(HttpMethod.GET, "/api/media/**").permitAll()
                        // 博客前台：组装页 + 无构建运行时（docs/blog-runtime-theme.md §5）
                        .requestMatchers(HttpMethod.GET, "/", "/posts/**", "/tags/**", "/archives",
                                "/search", "/about", "/p/**", "/blog/**", "/favicon.svg")
                        .permitAll()
                        // 三端接口域：要求已认证会话（client 一致性 + 权限码由过滤器 / 方法级校验）
                        .requestMatchers("/api/**").authenticated()
                        // 默认拒绝：API 服务不承载静态资源，非 API 路径一律拒绝
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(sessionJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
