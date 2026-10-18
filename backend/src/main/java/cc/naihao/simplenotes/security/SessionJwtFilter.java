package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.SysUserSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每请求认证过滤器（docs/auth-rbac.md §5.3），Bearer 凭证统一处理：
 *
 * <ol>
 *   <li>取 JWT → 验签 + exp（本地快速失败，明显伪造 / 过期不查库）；</li>
 *   <li>查会话行（jti）：存在 && 未撤销 && sub 一致 —— 每请求必查、不缓存（撤销通道）；</li>
 *   <li>空闲超时检查（顺带撤销）+ 滑动续期（超过阈值才回写）；</li>
 *   <li>会话端一致性：会话 client 必须与请求路径域匹配（防止 blog 会话调 admin 接口）；</li>
 *   <li>取授权快照（用户粒度进程内缓存，bump 即时失效）；</li>
 *   <li>入口即时回收：快照不含 {@code entry:{会话 client}} → 撤销会话并 401（超管直通）。</li>
 * </ol>
 *
 * <p>无效凭证 / 已撤销会话按<b>匿名</b>处理：无凭证或凭证不适用时保持匿名，由 SecurityConfig
 * 的路径规则决定是否 401（公开接口不受陈旧凭证影响）；入口失效与统一 401 / 403 出口见下。
 */
@Component
@RequiredArgsConstructor
public class SessionJwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final AuthzCache authzCache;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    /** 只处理 API 路径；其余请求（错误页等）不受影响 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        JwtService.JwtPayload payload;
        try {
            payload = jwtService.verify(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 无效凭证按匿名处理：受保护接口由授权规则返回 401；公开接口（登录 / blog 读）不受陈旧凭证影响
            filterChain.doFilter(request, response);
            return;
        }

        SysUserSession session = sessionService.validateAndTouch(payload.sessionId(), payload.userId())
                .orElse(null);
        if (session == null) {
            // 会话不存在 / 已撤销 / 空闲或绝对超时：同样按匿名处理（受保护接口 → 401）
            filterChain.doFilter(request, response);
            return;
        }

        // 会话端一致性：会话 client 必须与请求路径域匹配（防 blog 会话调 admin 接口）。
        // 不匹配的凭证在本域视为无效：保持匿名，由路径规则决定 401（受保护接口）或放行（公开读接口）
        ClientType pathClient = ClientType.fromPath(request.getRequestURI());
        if (pathClient != null && pathClient != session.getClient()) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthzSnapshot snapshot = authzCache.get(session.getUserId());
        if (!snapshot.root() && !snapshot.codes().contains(session.getClient().entryCode())) {
            // 入口失效（停用 entry 节点 / 角色被收走 entry）：撤销该会话并 401，效果等同"下一次请求即被踢"
            sessionService.revoke(session.getId());
            unauthorized(request, response, "登录入口已失效");
            return;
        }

        AuthPrincipal principal = new AuthPrincipal(session.getUserId(), session.getClient(),
                session.getId(), snapshot.root(), snapshot.codes());
        List<SimpleGrantedAuthority> authorities = snapshot.codes().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        authenticationEntryPoint.commence(request, response,
                new InsufficientAuthenticationException(reason));
    }
}
